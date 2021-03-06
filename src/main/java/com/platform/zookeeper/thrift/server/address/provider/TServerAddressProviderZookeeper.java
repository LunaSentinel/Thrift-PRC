package com.platform.zookeeper.thrift.server.address.provider;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * 使用zookeeper作为"config"中心
 */
public class TServerAddressProviderZookeeper implements TServerAddressProvider, InitializingBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(TServerAddressProviderZookeeper.class);

    public static final String CHARSET_NAME = "utf-8";
    public static final String IP_ADDRESS_SPLIT = ":";
    public static final int IP_ADDRESS_LENGTH = 3;

    private static final Integer DEFAULT_WEIGHT = 1;
    private static final boolean CACHE_DATA = true;

    private CuratorFramework zkClient;

    private PathChildrenCache cachedPath;

    private Object lock = new Object();

    // 用来保存当前provider所接触过的地址记录
    // 当zookeeper集群故障时,可以使用trace中地址,作为"备份"
    private Set<String> trace = Sets.newHashSet();

    private final List<InetSocketAddress> container = Lists.newArrayList();
    private Queue<InetSocketAddress> inner = Lists.newLinkedList();

    // 注册服务
    private String service;

    // 服务版本号
    private String version = "1.0.0";

    public void setService(String service) {
        this.service = service;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public TServerAddressProviderZookeeper() {
    }

    public TServerAddressProviderZookeeper(CuratorFramework zkClient) {
        this.zkClient = zkClient;
    }

    public void setZkClient(CuratorFramework zkClient) {
        this.zkClient = zkClient;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // 如果zk尚未启动,则启动
        if (zkClient.getState() == CuratorFrameworkState.LATENT) {
            zkClient.start();
        }
        buildPathChildrenCache(zkClient, getServicePath(), CACHE_DATA);
        cachedPath.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
    }

    private String getServicePath(){
        return "/" + service + "/" + version;
    }

    private void buildPathChildrenCache(final CuratorFramework client, String path, Boolean cacheData) {
        cachedPath = new PathChildrenCache(client, path, cacheData);
        cachedPath.getListenable().addListener(new PathChildrenCacheListener() {
            @Override
            public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                PathChildrenCacheEvent.Type eventType = event.getType();
                switch (eventType) {
                    case CONNECTION_RECONNECTED:
                        LOGGER.info("Connection is reconection...");
                        break;
                    case CONNECTION_SUSPENDED:
                        LOGGER.info("Connection is suspended...");
                        break;
                    case CONNECTION_LOST:
                        LOGGER.warn("Connection error,waiting...");
                        return;
                    default:
                }
                // 任何节点的时机数据变动,都会rebuild.
                cachedPath.rebuild();
                rebuild();
            }

            protected void rebuild() throws Exception {
                List<ChildData> children = cachedPath.getCurrentData();
                if (children == null || children.isEmpty()) {
                    // 所有的thrift server都与zookeeper断开了链接,
                    // 但是可能thrift client与thrift server之间的网络是良好的,
                    // 所以此处保留容器
                    //container.clear();
                    LOGGER.error("thrift server-cluster error....");
                    return;
                }

                List<InetSocketAddress> current = Lists.newArrayList();
                String path = null;
                for (ChildData data : children) {
                    path = data.getPath();
                    LOGGER.debug("get path:" + path);
                    path = path.substring(getServicePath().length() + 1);
                    LOGGER.debug("get serviceAddress:" + path);
                    String address = new String(path.getBytes(), CHARSET_NAME);
                    current.addAll(transfer(address));
                    trace.add(address);
                }

                Collections.shuffle(current);
                synchronized (lock) {
                    container.clear();
                    container.addAll(current);
                    inner.clear();
                    inner.addAll(current);
                }
            }
        });
    }

    private List<InetSocketAddress> transfer(String address) {
        String[] hostname = address.split(IP_ADDRESS_SPLIT);
        Integer weight = DEFAULT_WEIGHT;
        if (hostname.length == IP_ADDRESS_LENGTH) {
            weight = Integer.valueOf(hostname[2]);
        }
        String ip = hostname[0];
        Integer port = Integer.valueOf(hostname[1]);
        List<InetSocketAddress> result = Lists.newArrayList();
        // 根据优先级，将ip：port添加多次到地址集中，然后随机取地址实现负载
        for (int i = 0; i < weight; i++) {
            result.add(new InetSocketAddress(ip, port));
        }
        return result;
    }

    @Override
    public List<InetSocketAddress> findServerAddressList() {
        return Collections.unmodifiableList(container);
    }

    @Override
    public synchronized InetSocketAddress selector() {
        if (inner.isEmpty()) {
            if (!container.isEmpty()) {
                inner.addAll(container);
            } else if (!trace.isEmpty()) {
                synchronized (lock) {
                    for (String hostname : trace) {
                        container.addAll(transfer(hostname));
                    }
                    Collections.shuffle(container);
                    inner.addAll(container);
                }
            }
        }
        return inner.poll();
    }

    @Override
    public void close() {
        try {
            cachedPath.close();
            zkClient.close();
        } catch (Exception e) {
        }
    }

    @Override
    public String getService() {
        return service;
    }

}
