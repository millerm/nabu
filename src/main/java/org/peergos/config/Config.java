package org.peergos.config;

import io.ipfs.multiaddr.MultiAddress;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;
import org.peergos.HostBuilder;
import org.peergos.util.JSONParser;
import org.peergos.util.JsonHelper;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Config {

    public final AddressesSection addresses;
    public final BootstrapSection bootstrap;
    public final DatastoreSection datastore;
    public final IdentitySection identity;
    public final MetricsSection metrics;

    public static final List<MultiAddress> defaultBootstrapNodes = new ArrayList<MultiAddress>();

    public Config() {
        Config config = defaultConfig(Optional.empty());
        this.addresses = config.addresses;
        this.bootstrap = config.bootstrap;
        this.datastore = config.datastore;
        this.identity = config.identity;
        this.metrics = config.metrics;
    }
    public Config(Supplier<Mount> dataStoreSupplier) {
        Config config = defaultConfig(Optional.of(dataStoreSupplier));
        this.addresses = config.addresses;
        this.bootstrap = config.bootstrap;
        this.datastore = config.datastore;
        this.identity = config.identity;
        this.metrics = config.metrics;
    }
    public Config(AddressesSection addresses, BootstrapSection bootstrap, DatastoreSection datastore,
                  IdentitySection identity, MetricsSection metrics) {
        this.addresses = addresses;
        this.bootstrap = bootstrap;
        this.datastore = datastore;
        this.identity = identity;
        this.metrics = metrics;
        validate(this);
    }

    public static Config build(String contents) {
        Map<String, Object> json = (Map) JSONParser.parse(contents);
        AddressesSection addressesSection = Jsonable.parse(json, p -> AddressesSection.fromJson(p));
        BootstrapSection bootstrapSection = Jsonable.parse(json, p -> BootstrapSection.fromJson(p));
        DatastoreSection datastoreSection = Jsonable.parse(json, p -> DatastoreSection.fromJson(p));
        IdentitySection identitySection = Jsonable.parse(json, p -> IdentitySection.fromJson(p));
        MetricsSection metricsSection = Jsonable.parse(json, p -> MetricsSection.fromJson(p));
        return new Config(addressesSection, bootstrapSection, datastoreSection, identitySection, metricsSection);
    }

    @Override
    public String toString() {
        Map<String, Object> configMap = new LinkedHashMap<>();
        configMap.putAll(addresses.toJson());
        configMap.putAll(bootstrap.toJson());
        configMap.putAll(datastore.toJson());
        configMap.putAll(metrics.toJson());
        configMap.putAll(identity.toJson());
        return JsonHelper.pretty(configMap);
    }

    public Config defaultConfig(Optional<Supplier<Mount>> dataStoreSupplier) {
        HostBuilder builder = new HostBuilder().generateIdentity();
        PrivKey privKey = builder.getPrivateKey();
        PeerId peerId = builder.getPeerId();

        List<MultiAddress> swarmAddresses = List.of(new MultiAddress("/ip6/::/tcp/4001"));
        MultiAddress apiAddress = new MultiAddress("/ip4/127.0.0.1/tcp/5001");
        MultiAddress gatewayAddress = new MultiAddress("/ip4/127.0.0.1/tcp/8080");
        Optional<MultiAddress> proxyTargetAddress = Optional.of(new MultiAddress("/ip4/127.0.0.1/tcp/8003"));

        Optional<String> allowTarget = Optional.of("http://localhost:8002");
        List<MultiAddress> bootstrapNodes = new ArrayList<>(defaultBootstrapNodes);
        Mount blockMount = null;
        if (dataStoreSupplier.isPresent()) {
            blockMount = dataStoreSupplier.get().get();
        } else {
            Map<String, Object> blockChildMap = new LinkedHashMap<>();
            blockChildMap.put("path", "blocks");
            blockChildMap.put("shardFunc", "/repo/flatfs/shard/v1/next-to-last/2");
            blockChildMap.put("sync", "true");
            blockChildMap.put("type", "flatfs");
            blockMount = new Mount("/blocks", "flatfs.datastore", "measure", blockChildMap);
        }
        Map<String, Object> dataChildMap = new LinkedHashMap<>();
        dataChildMap.put("compression", "none");
        dataChildMap.put("path", "datastore");
        dataChildMap.put("type", "h2");
        Mount rootMount = new Mount("/", "h2.datastore", "measure", dataChildMap);

        AddressesSection addressesSection = new AddressesSection(swarmAddresses, apiAddress, gatewayAddress,
                proxyTargetAddress, allowTarget);
        Filter filter = new Filter(FilterType.NONE, 0.0);
        CodecSet codecSet = CodecSet.empty();
        DatastoreSection datastoreSection = new DatastoreSection(blockMount, rootMount, filter, codecSet);
        BootstrapSection bootstrapSection = new BootstrapSection(bootstrapNodes);
        IdentitySection identitySection = new IdentitySection(privKey.bytes(), peerId);
        MetricsSection metricsSection = MetricsSection.defaultConfig();
        return new Config(addressesSection, bootstrapSection, datastoreSection, identitySection, metricsSection);
    }

    public void validate(Config config) {

        if (config.addresses.getSwarmAddresses().isEmpty()) {
            throw new IllegalStateException("Expecting Addresses/Swarm entries");
        }
        Mount blockMount = config.datastore.blockMount;
        if (!( (blockMount.prefix.equals("flatfs.datastore")  || blockMount.prefix.equals("s3.datastore"))
                && blockMount.type.equals("measure"))) {
            throw new IllegalStateException("Expecting /blocks mount to have prefix == ('flatfs.datastore' or 's3.datastore') and type == 'measure'");
        }
        Map<String, Object> blockParams = blockMount.getParams();
        String blockPath = (String) blockParams.get("path");
        String blockShardFunc = (String) blockParams.get("shardFunc");
        String blockType = (String) blockParams.get("type");
        if (blockType.equals("flatfs") && !(blockPath.equals("blocks") && blockShardFunc.equals("/repo/flatfs/shard/v1/next-to-last/2"))) {
            throw new IllegalStateException("Expecting flatfs mount at /blocks");
        }
        if (blockMount.prefix.equals("s3.datastore") && !blockType.equals("s3ds")) {
            throw new IllegalStateException("Expecting /blocks s3.datastore mount to have a type of 's3ds'");
        }

        Mount rootMount = config.datastore.rootMount;
        if (!(rootMount.prefix.equals("h2.datastore") && rootMount.type.equals("measure"))) {
            throw new IllegalStateException("Expecting / mount to have prefix == 'h2.datastore' and type == 'measure'");
        }
        Map<String, Object> rootParams = rootMount.getParams();
        String rootPath = (String) rootParams.get("path");
        String rootCompression = (String) rootParams.get("compression");
        String rootType = (String) rootParams.get("type");
        if (!(rootPath.equals("datastore") && rootCompression.equals("none") && rootType.equals("h2"))) {
            throw new IllegalStateException("Expecting flatfs mount at /");
        }

        MetricsSection metricsSection = config.metrics;
        if (metricsSection.enabled) {
            if (metricsSection.address.trim().length() == 0) {
                throw new IllegalStateException("Expecting metrics address to be set");
            }
            if (metricsSection.port < 1024) {
                throw new IllegalStateException("Expecting metrics port to be >= 1024");
            }
        }
    }
}
