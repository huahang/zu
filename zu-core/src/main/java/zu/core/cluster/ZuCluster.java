package zu.core.cluster;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.ImmutableSet;
import com.twitter.common.net.pool.DynamicHostSet.HostChangeMonitor;
import com.twitter.common.net.pool.DynamicHostSet.MonitorException;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.zookeeper.Group.JoinException;
import com.twitter.common.zookeeper.ServerSet;
import com.twitter.common.zookeeper.ServerSet.EndpointStatus;
import com.twitter.common.zookeeper.ServerSet.UpdateException;
import com.twitter.common.zookeeper.ServerSetImpl;
import com.twitter.common.zookeeper.ZooKeeperClient;
import com.twitter.common.zookeeper.ZooKeeperClient.Credentials;
import com.twitter.thrift.Endpoint;
import com.twitter.thrift.ServiceInstance;

public class ZuCluster implements HostChangeMonitor<ServiceInstance>{
  private static final int DEFAULT_TIMEOUT = 300;
  private final ServerSet serverSet;
  private final List<ZuClusterEventListener> lsnrs;
  
  private static class NodeClusterView{
    Map<Endpoint,InetSocketAddress> nodesMap = new HashMap<Endpoint,InetSocketAddress>();
    Map<Integer,ArrayList<InetSocketAddress>> partMap = new HashMap<Integer,ArrayList<InetSocketAddress>>();
  }
  
  private AtomicReference<NodeClusterView> clusterView = new AtomicReference<NodeClusterView>(new NodeClusterView());

  public ZuCluster(String host, int port, String clusterName) throws MonitorException {
    this(new InetSocketAddress(host,port), clusterName, DEFAULT_TIMEOUT);
  }
  
  public ZuCluster(String host, int port, String clusterName,
      int timeout) throws MonitorException {
    this(new InetSocketAddress(host,port), clusterName, timeout);
  }
  
  public ZuCluster(InetSocketAddress zookeeperAddr, String clusterName) throws MonitorException{
    this(zookeeperAddr, clusterName, DEFAULT_TIMEOUT);
  }
  
  public ZuCluster(InetSocketAddress zookeeperAddr, String clusterName,
      int timeout) throws MonitorException{
    assert zookeeperAddr != null;
    assert clusterName != null;
    lsnrs = Collections.synchronizedList(new LinkedList<ZuClusterEventListener>());
    ZooKeeperClient zclient = new ZooKeeperClient(Amount.of(timeout,
        Time.SECONDS), Credentials.NONE, zookeeperAddr);
    
    if (!clusterName.startsWith("/")){
      clusterName = "/" + clusterName;
    }
    serverSet = new ServerSetImpl(zclient, clusterName);
    serverSet.monitor(this);
  }
  
  public void addClusterEventListener(ZuClusterEventListener lsnr){
    lsnrs.add(lsnr);
  }

  public List<EndpointStatus> join(InetSocketAddress addr, List<Integer> shards) throws JoinException, InterruptedException {
    ArrayList<EndpointStatus> statuses = new ArrayList<EndpointStatus>(shards.size());
    for (Integer shard : shards){
      statuses.add(serverSet.join(addr, Collections.<String, InetSocketAddress>emptyMap(), shard));
      Thread.sleep(5000);
    }
    System.out.println(statuses.size()+" joined");
    return statuses;
  }
  
  public void leave(List<EndpointStatus> statuses) throws UpdateException{
    for (EndpointStatus status : statuses){
      status.leave();
    }
  }


  @Override
  public void onChange(ImmutableSet<ServiceInstance> hostSet) {

    System.out.println("hostSet: "+hostSet.size());
    NodeClusterView oldView = clusterView.get();
    NodeClusterView newView = new NodeClusterView();
    List<InetSocketAddress> cleanupList = new LinkedList<InetSocketAddress>();
    
    for (ServiceInstance si : hostSet){
      
      Endpoint ep = si.getServiceEndpoint();

      InetSocketAddress svc = oldView.nodesMap.get(ep);
      InetSocketAddress sa = new InetSocketAddress(ep.getHost(), ep.getPort());
      if (svc == null){
        // discovered a new node
        svc = sa;
      }
      newView.nodesMap.put(ep, svc);
      int shardId = si.getShard();
      System.out.println("shardid: "+shardId);
      ArrayList<InetSocketAddress> nodeList = newView.partMap.get(shardId);
      if (nodeList == null){
        nodeList = new ArrayList<InetSocketAddress>();
        newView.partMap.put(shardId, nodeList);
      }
      nodeList.add(svc);
    }
    
 // gather a list of clients that are no longer in the cluster and cleanup
    Set<Entry<Endpoint,InetSocketAddress>> entries = oldView.nodesMap.entrySet();
    Set<Endpoint> newEndpoints = newView.nodesMap.keySet();
    for (Entry<Endpoint,InetSocketAddress> entry : entries){
      if (!newEndpoints.contains(entry.getKey())){
        cleanupList.add(entry.getValue());
      }
    }

    clusterView.set(newView);
    
    System.out.println("new view: "+newView.partMap.size());
    
    for (ZuClusterEventListener lsnr : lsnrs){
      System.out.println("notify new view");
     lsnr.clusterChanged(newView.partMap); 
    }
  }

}
