package org.peergos;

import io.libp2p.core.*;
import org.peergos.blockstore.*;
import org.peergos.protocol.dht.*;
import org.peergos.util.Admin;
import org.peergos.util.Config;
import org.peergos.util.InstanceAdmin;
import org.peergos.util.JSONParser;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

public class Server {

    public Server() {
        try {
            Config config = readConfig();
            InstanceAdmin.VersionInfo version = new InstanceAdmin.VersionInfo(APIService.CURRENT_VERSION, Admin.getSourceVersion());
            System.out.println("Starting Nabu version: " + version);
            String privKey = config.getProperty("Identity", "PrivKey");

            int hostPort = 6001; // 6001

            String specType = config.getProperty("Datastore", "Spec", "type");
            if (!specType.equals("mount")) {
                throw new IllegalStateException("Unable to read mount configuration");
            }
            List<Map> mounts = config.getPropertyList("Datastore", "Spec", "mounts");
            Optional<Map> blockMountOpt = mounts.stream().filter(m -> m.get("mountpoint").equals("/blocks")).findFirst();
            Config blockConfig = new Config(blockMountOpt.get());
            String blockPath = blockConfig.getProperty("child", "path");
            String blockShardFunc = blockConfig.getProperty("child", "shardFunc");
            String blockType = blockConfig.getProperty("child", "type");
            if (!(blockPath.equals("blocks") && blockShardFunc.equals("/repo/flatfs/shard/v1/next-to-last/2")
                && blockType.equals("flatfs"))) {
                throw new IllegalStateException("Expecting flatfs mount at /blocks");
            }
            Path blocksPath = Path.of(System.getenv("HOME"), ".ipfs", "blocks");
            FileBlockstore storage = new FileBlockstore(blocksPath);
            RecordStore recordStore = new RamRecordStore();
            HostBuilder builder = HostBuilder.build(privKey, hostPort, new RamProviderStore(), recordStore, storage);
            Host node = builder.build();
            node.start().join();
            System.out.println("Node started and listening on " + node.listenAddresses());

//            Multiaddr bootstrapNode = Multiaddr.fromString("/dnsaddr/bootstrap.libp2p.io/p2p/QmcZf59bWwK5XFi76CZX8cbJ4BhTzzA3gU1ZjYZcYW3dwt");
//            KademliaController bootstrap = builder.getWanDht().get().dial(node, bootstrapNode).getController().join();

            Thread shutdownHook = new Thread(() -> {
                System.out.println("Stopping server...");
                try {
                    node.stop().get();
                } catch (InterruptedException | ExecutionException ex) {
                    ex.printStackTrace();
                }
            });
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            APIService localAPI = new APIService();
            String apiStr = config.getProperty("Addresses", "API");
            String apiAddr = "/ip4/127.0.0.1/tcp/";
            String apiPortStr = apiStr.startsWith(apiAddr) ? apiStr.substring(apiAddr.length()) : null;
            if (apiPortStr == null) {
                throw new IllegalStateException("Unable to determine API port");
            }
            int apiPort = Integer.parseInt(apiPortStr); // 5001
            InetSocketAddress localAPIAddress = new InetSocketAddress("localhost", apiPort);

            int maxConnectionQueue = 500;
            int handlerThreads = 50;
            localAPI.initAndStart(localAPIAddress, node, storage, maxConnectionQueue, handlerThreads);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private Config readConfig() throws IOException {
        String home = System.getenv("HOME");
        Path configFilePath = Path.of(home, ".ipfs", "config");
        File configFile = configFilePath.toFile();
        if (! configFile.exists()) {
            throw new IllegalStateException("Unable to find ./ipfs/config file");
        }
        String contents = Files.readString(configFilePath);
        return new Config((HashMap) JSONParser.parse(contents));
    }
    public static void main(String[] args) {
        new Server();
    }
}