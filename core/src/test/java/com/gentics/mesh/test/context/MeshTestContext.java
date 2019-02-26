package com.gentics.mesh.test.context;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.testcontainers.containers.wait.Wait;

import com.gentics.mesh.Mesh;
import com.gentics.mesh.cli.BootstrapInitializerImpl;
import com.gentics.mesh.core.cache.PermissionStore;
import com.gentics.mesh.core.data.impl.DatabaseHelper;
import com.gentics.mesh.core.data.search.IndexHandler;
import com.gentics.mesh.core.rest.MeshEvent;
import com.gentics.mesh.crypto.KeyStoreHelper;
import com.gentics.mesh.dagger.DaggerMeshComponent;
import com.gentics.mesh.dagger.MeshComponent;
import com.gentics.mesh.dagger.MeshInternal;
import com.gentics.mesh.etc.config.HttpServerConfig;
import com.gentics.mesh.etc.config.MeshOptions;
import com.gentics.mesh.etc.config.OAuth2Options;
import com.gentics.mesh.etc.config.OAuth2ServerConfig;
import com.gentics.mesh.graphdb.spi.Database;
import com.gentics.mesh.impl.MeshFactoryImpl;
import com.gentics.mesh.rest.client.MeshRestClient;
import com.gentics.mesh.rest.client.MeshRestClientConfig;
import com.gentics.mesh.rest.client.impl.MeshRestOkHttpClientImpl;
import com.gentics.mesh.router.RouterStorage;
import com.gentics.mesh.search.TrackingSearchProvider;
import com.gentics.mesh.search.verticle.ElasticsearchProcessVerticle;
import com.gentics.mesh.test.TestDataProvider;
import com.gentics.mesh.test.TestSize;
import com.gentics.mesh.test.docker.ElasticsearchContainer;
import com.gentics.mesh.test.docker.KeycloakContainer;
import com.gentics.mesh.test.util.TestUtils;
import com.gentics.mesh.util.UUIDUtil;
import com.syncleus.ferma.tx.Tx;

import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import okhttp3.OkHttpClient;

public class MeshTestContext extends TestWatcher {

	static {
		System.setProperty("mesh.test", "true");
	}

	private static final Logger log = LoggerFactory.getLogger(MeshTestContext.class);

	private static final String CONF_PATH = "target/config-" + System.currentTimeMillis();

	private static MeshOptions meshOptions = new MeshOptions();

	public static ElasticsearchContainer elasticsearch;

	public static KeycloakContainer keycloak;

	public static OkHttpClient okHttp = new OkHttpClient.Builder()
		.callTimeout(Duration.ofMinutes(15))
		.connectTimeout(Duration.ofMinutes(15))
		.writeTimeout(Duration.ofMinutes(15))
		.readTimeout(Duration.ofMinutes(15))
		.build();

	private List<File> tmpFolders = new ArrayList<>();
	private MeshComponent meshDagger;
	private TestDataProvider dataProvider;
	private TrackingSearchProvider trackingSearchProvider;
	private Vertx vertx;

	protected int port;

	private MeshRestClient client;

	private List<String> deploymentIds = new ArrayList<>();

	private CountDownLatch idleLatch;
	private MessageConsumer<Object> idleConsumer;

	@Override
	protected void starting(Description description) {
		try {
			MeshTestSetting settings = getSettings(description);
			// Setup the dagger context and orientdb,es once
			if (description.isSuite()) {
				port = TestUtils.getRandomPort();
				removeDataDirectory();
				removeConfigDirectory();
				MeshOptions options = init(settings);
				initDagger(options, settings.testSize());
				meshDagger.boot().registerEventHandlers();
			} else {
				if (!settings.inMemoryDB()) {
					DatabaseHelper.init(meshDagger.database());
				}
				initFolders(Mesh.mesh().getOptions());
				setupData();
				if (settings.useElasticsearch()) {
					setupIndexHandlers();
					listenToSearchIdleEvent();
				}
				if (settings.startServer()) {
					setupRestEndpoints(settings);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	@Override
	protected void finished(Description description) {
		try {
			MeshTestSetting settings = getSettings(description);
			if (description.isSuite()) {
				removeDataDirectory();
				removeConfigDirectory();
				if (elasticsearch != null && elasticsearch.isRunning()) {
					elasticsearch.stop();
				}
				if (keycloak != null && keycloak.isRunning()) {
					keycloak.stop();
				}
			} else {
				cleanupFolders();
				if (settings.startServer()) {
					undeployAndReset();
					closeClient();
				}
				if (settings.useElasticsearch()) {
					meshDagger.searchProvider().clear().blockingAwait();
					idleConsumer.unregister();
				} else {
					meshDagger.trackingSearchProvider().clear();
				}
				resetDatabase(settings);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void removeConfigDirectory() throws IOException {
		FileUtils.deleteDirectory(new File(CONF_PATH));
		System.setProperty("mesh.confDirName", CONF_PATH);
	}

	private void removeDataDirectory() throws IOException {
		FileUtils.deleteDirectory(new File("data"));
	}

	protected void setupIndexHandlers() throws Exception {
		// We need to call init() again in order create missing indices for the created test data
		for (IndexHandler<?> handler : meshDagger.indexHandlerRegistry().getHandlers()) {
			handler.init().blockingAwait();
		}
	}

	/**
	 * Set Features according to the method annotations
	 * 
	 * @param description
	 */
	protected MeshTestSetting getSettings(Description description) {
		Class<?> testClass = description.getTestClass();
		if (testClass != null) {
			return testClass.getAnnotation(MeshTestSetting.class);
		}
		return description.getAnnotation(MeshTestSetting.class);
	}

	private void setupRestEndpoints(MeshTestSetting settings) throws Exception {
		Mesh.mesh().getOptions().getUploadOptions().setByteLimit(Long.MAX_VALUE);

		log.info("Using port:  " + port);
		RouterStorage.addProject(TestDataProvider.PROJECT_NAME);

		// Setup the rest client
		try (Tx tx = db().tx()) {
			boolean ssl = settings.ssl();
			MeshRestClientConfig clientConfig = new MeshRestClientConfig.Builder()
				.setHost("localhost")
				.setPort(port)
				.setSsl(ssl)
				.build();
			client = new MeshRestOkHttpClientImpl(clientConfig, okHttp);
			client.setLogin(getData().user().getUsername(), getData().getUserInfo().getPassword());
			client.login().blockingGet();
		}
		if (trackingSearchProvider != null) {
			trackingSearchProvider.clear().blockingAwait();
		}
	}

	private Database db() {
		return meshDagger.database();
	}

	public int getPort() {
		return port;
	}

	public Vertx getVertx() {
		return vertx;
	}

	/**
	 * Setup the test data.
	 * 
	 * @throws Exception
	 */
	private void setupData() throws Exception {
		meshDagger.database().setMassInsertIntent();
		dataProvider.setup();
		meshDagger.database().resetIntent();
	}

	private void undeployAndReset() throws Exception {
		for (String id : deploymentIds) {
			vertx.undeploy(id);
		}
	}

	private void closeClient() throws Exception {
		if (client != null) {
			try {
				client.close();
			} catch (IllegalStateException e) {
				// Ignored
				e.printStackTrace();
			}
		}
	}

	/**
	 * Clear the test data.
	 * 
	 * @param settings
	 * @throws Exception
	 */
	private void resetDatabase(MeshTestSetting settings) throws Exception {
		BootstrapInitializerImpl.clearReferences();
		Database db = MeshInternal.get().database();
		long start = System.currentTimeMillis();
		if (settings.inMemoryDB()) {
			db.clear();
		} else {
			db.stop();
			File dbDir = new File(Mesh.mesh().getOptions().getStorageOptions().getDirectory());
			FileUtils.deleteDirectory(dbDir);
			db.setupConnectionPool();
		}
		long duration = System.currentTimeMillis() - start;
		log.info("Clearing DB took {" + duration + "} ms.");
		if (trackingSearchProvider != null) {
			trackingSearchProvider.reset();
		}
	}

	private void cleanupFolders() throws IOException {
		for (File folder : tmpFolders) {
			FileUtils.deleteDirectory(folder);
		}
		PermissionStore.invalidate(false);
	}

	public TestDataProvider getData() {
		return dataProvider;
	}

	public TrackingSearchProvider getTrackingSearchProvider() {
		return trackingSearchProvider;
	}

	/**
	 * Initialise mesh options.
	 * 
	 * @param settings
	 * @throws Exception
	 */
	public MeshOptions init(MeshTestSetting settings) throws Exception {
		MeshFactoryImpl.clear();

		if (settings == null) {
			throw new RuntimeException("Settings could not be found. Did you forgot to add the @MeshTestSetting annotation to your test?");
		}
		// Clustering options
		if (settings.clusterMode()) {
			meshOptions.getClusterOptions().setEnabled(true);
			meshOptions.setInitCluster(true);
			meshOptions.getClusterOptions().setClusterName("cluster" + System.currentTimeMillis());
		}

		// Setup the keystore
		File keystoreFile = new File("target", "keystore_" + UUIDUtil.randomUUID() + ".jceks");
		keystoreFile.deleteOnExit();
		String keystorePassword = "finger";
		if (!keystoreFile.exists()) {
			KeyStoreHelper.gen(keystoreFile.getAbsolutePath(), keystorePassword);
		}
		meshOptions.getAuthenticationOptions().setKeystorePassword(keystorePassword);
		meshOptions.getAuthenticationOptions().setKeystorePath(keystoreFile.getAbsolutePath());
		meshOptions.setNodeName("testNode");

		initFolders(meshOptions);

		HttpServerConfig httpOptions = meshOptions.getHttpServerOptions();
		httpOptions.setPort(port);
		if (settings.ssl()) {
			httpOptions.setSsl(true);
			httpOptions.setCertPath("src/test/resources/ssl/cert.pem");
			httpOptions.setKeyPath("src/test/resources/ssl/key.pem");
		}
		// The database provider will switch to in memory mode when no directory has been specified.

		String graphPath = null;
		if (!settings.inMemoryDB() || settings.clusterMode()) {
			graphPath = "target/graphdb_" + UUIDUtil.randomUUID();
			File directory = new File(graphPath);
			directory.deleteOnExit();
			directory.mkdirs();
		}
		if (!settings.inMemoryDB() && settings.startStorageServer()) {
			meshOptions.getStorageOptions().setStartServer(true);
		}
		// Increase timeout to high load during testing
		meshOptions.getSearchOptions().setTimeout(10_000L);
		meshOptions.getStorageOptions().setDirectory(graphPath);
		if (settings.useElasticsearchContainer()) {
			meshOptions.getSearchOptions().setStartEmbedded(false);
			meshOptions.getSearchOptions().setUrl(null);
			if (settings.useElasticsearch()) {
				elasticsearch = new ElasticsearchContainer(settings.withIngestPlugin());
				if (!elasticsearch.isRunning()) {
					elasticsearch.start();
				}
				elasticsearch.waitingFor(Wait.forHttp("/"));
				meshOptions.getSearchOptions().setUrl("http://localhost:" + elasticsearch.getMappedPort(9200));
			}
		}

		if (settings.useKeycloak()) {
			keycloak = new KeycloakContainer()
				.withRealmFromClassPath("/keycloak/realm.json");
			if (!keycloak.isRunning()) {
				keycloak.start();
			}
			keycloak.waitingFor(Wait.forListeningPort());
			OAuth2Options oauth2Options = meshOptions.getAuthenticationOptions().getOauth2();
			oauth2Options.setEnabled(true);

			OAuth2ServerConfig realmConfig = new OAuth2ServerConfig();
			realmConfig.setAuthServerUrl("http://localhost:" + keycloak.getFirstMappedPort() + "/auth");
			realmConfig.setRealm("master-test");
			realmConfig.setSslRequired("external");
			realmConfig.setResource("mesh");
			realmConfig.setConfidentialPort(0);
			realmConfig.addCredential("secret", "9b65c378-5b4c-4e25-b5a1-a53a381b5fb4");

			oauth2Options.setConfig(realmConfig);
		}

		Mesh.mesh(meshOptions);
		return meshOptions;
	}

	private void initFolders(MeshOptions meshOptions) throws IOException {
		String tmpDir = newFolder("tmpDir");
		meshOptions.setTempDirectory(tmpDir);

		String uploads = newFolder("testuploads");
		meshOptions.getUploadOptions().setDirectory(uploads);
		String targetUploadTmpDir = newFolder("uploadTmpDir");
		meshOptions.getUploadOptions().setTempDirectory(targetUploadTmpDir);

		String imageCacheDir = newFolder("image_cache");
		meshOptions.getImageOptions().setImageCacheDirectory(imageCacheDir);

		String backupPath = newFolder("backups");
		meshOptions.getStorageOptions().setBackupDirectory(backupPath);
		String exportPath = newFolder("exports");
		meshOptions.getStorageOptions().setExportDirectory(exportPath);
	}

	/**
	 * Create a new folder which will be automatically be deleted once the rule finishes.
	 * 
	 * @param prefix
	 * @return
	 * @throws IOException
	 */
	private String newFolder(String prefix) throws IOException {
		String path = "target/" + prefix + "_" + UUIDUtil.randomUUID();
		File directory = new File(path);
		FileUtils.deleteDirectory(directory);
		directory.deleteOnExit();
		assertTrue("Could not create dir for path {" + path + "}", directory.mkdirs());
		tmpFolders.add(directory);
		return path;
	}

	/**
	 * Initialise the mesh dagger context and inject the dependencies within the test.
	 * 
	 * @param options
	 * 
	 * @throws Exception
	 */
	public void initDagger(MeshOptions options, TestSize size) throws Exception {
		log.info("Initializing dagger context");
		meshDagger = DaggerMeshComponent.builder().configuration(options).build();
		MeshInternal.set(meshDagger);
		dataProvider = new TestDataProvider(size, meshDagger.boot(), meshDagger.database());
		if (meshDagger.searchProvider() instanceof TrackingSearchProvider) {
			trackingSearchProvider = meshDagger.trackingSearchProvider();
		}
		try {
			meshDagger.boot().init(Mesh.mesh(), false, options, null);
			vertx = Mesh.vertx();
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}

	public MeshRestClient getClient() {
		return client;
	}

	public static KeycloakContainer getKeycloak() {
		return keycloak;
	}

	public MeshOptions getOptions() {
		return meshOptions;
	}

	private void listenToSearchIdleEvent() {
		idleConsumer = vertx.eventBus().consumer(MeshEvent.SEARCH_IDLE.address, handler -> {
			log.info("Got search idle event");
			if (idleLatch != null) {
				idleLatch.countDown();
			}
		});
	}

	/**
	 * Waits until all requests have been sent successfully.
	 */
	public void waitForSearchIdleEvent() {
		Objects.requireNonNull(idleConsumer, "Call #listenToSearchIdleEvent first");
		ElasticsearchProcessVerticle verticle = ((BootstrapInitializerImpl) meshDagger.boot()).loader.get().elasticsearchProcessVerticle.get();
		try {
			verticle.flush();
			idleLatch = new CountDownLatch(1);
			boolean timedout = idleLatch.await(30, TimeUnit.SECONDS);
			if (timedout) {
				throw new RuntimeException("Timed out while waiting for search idle event");
			}
			verticle.refresh().blockingAwait();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}
