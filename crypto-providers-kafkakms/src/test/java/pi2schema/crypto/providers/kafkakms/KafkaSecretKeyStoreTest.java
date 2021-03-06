package pi2schema.crypto.providers.kafkakms;

import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializerConfig;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.Rule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import pi2schema.crypto.support.KeyGen;
import pi2schema.kms.KafkaProvider.SubjectCryptographicMaterialAggregate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pi2schema.crypto.providers.kafkakms.KafkaTestUtils.createTopics;

@Testcontainers
class KafkaSecretKeyStoreTest {

    @Rule
    public KafkaContainer kafka = new KafkaContainer().withNetwork(Network.SHARED);

    public GenericContainer schemaRegistry;
    private Map<String, Object> configs;
    private KafkaSecretKeyStore store;

    @BeforeEach
    void setUp() {
        kafka.start();

        schemaRegistry = new GenericContainer("confluentinc/cp-schema-registry:5.5.1")
                .withNetwork(Network.SHARED)
                .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://" + kafka.getNetworkAliases().get(0) + ":9092")
                .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
                .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
                .withExposedPorts(8081);

        schemaRegistry.start();

        String schemaRegistryUrl = "http://" + schemaRegistry.getContainerIpAddress() +
                ":" + schemaRegistry.getMappedPort(8081);

        configs = new HashMap<>();
        configs.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        configs.put(KafkaProtobufSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);

        createTopics(configs, "pi2schema.kms.commands");

        store = new KafkaSecretKeyStore(KeyGen.aes256(), configs);
    }

    @AfterEach
    void tearDown() {
        store.close();
        schemaRegistry.close();
        kafka.close();
    }

    @Test
    void getOrCreate() {
        var subject = UUID.randomUUID().toString();

        // create
        var firstMaterials = store.retrieveOrCreateCryptoMaterialsFor(subject).join();

        assertThat(firstMaterials.getMaterialsList()).hasSize(1);
        assertThat(firstMaterials.getMaterials(0).getAlgorithm())
                .isEqualTo("AES");
        assertThat(firstMaterials.getMaterials(0).getSymmetricKey())
                .isNotEmpty();

        // retrieve
        // the key propagation is asynchronous
        await().atMost(Duration.ofSeconds(120)).untilAsserted(
                () -> {
                    SubjectCryptographicMaterialAggregate retrievedMaterials = store.existentMaterialsFor(subject).join();
                    assertThat(retrievedMaterials).isEqualTo(firstMaterials);
                }
        );

        // once the key is propagated, should reuse the previous key and not create new ones
        var retrieveOrCreateSecond = store.retrieveOrCreateCryptoMaterialsFor(subject).join();

        assertThat(firstMaterials).isEqualTo(retrieveOrCreateSecond);
    }
}