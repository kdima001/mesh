package com.gentics.mesh.search;

import static com.gentics.mesh.test.context.ElasticsearchTestMode.CONTAINER_ES6;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.gentics.mesh.core.data.search.index.IndexInfo;
import com.gentics.mesh.search.impl.ElasticSearchProvider;
import com.gentics.mesh.test.TestSize;
import com.gentics.mesh.test.context.AbstractMeshTest;
import com.gentics.mesh.test.context.MeshTestSetting;
import com.gentics.mesh.util.UUIDUtil;

import io.reactivex.Observable;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
@MeshTestSetting(elasticsearch = CONTAINER_ES6, testSize = TestSize.PROJECT_AND_NODE, startServer = true)
public class ElasticSearchProviderTest extends AbstractMeshTest {

	@Test
	public void testProvider() throws IOException {
		ElasticSearchProvider provider = getProvider();
		provider.createIndex(new IndexInfo("test", new JsonObject(), new JsonObject(), "testSchema")).blockingAwait();
		String uuid = UUIDUtil.randomUUID();
		// provider.storeDocument("test", uuid, new JsonObject()).blockingAwait();
		provider.updateDocument("test", uuid, new JsonObject(), true).blockingAwait();

		provider.deleteDocument("test", uuid).blockingAwait();

		// Should not fail if the document is gone since we end result would be the same.
		provider.deleteDocument("test", uuid).blockingAwait();

		provider.deleteIndex("testindex").blockingAwait();

		provider.createIndex(new IndexInfo("testindex", new JsonObject(), new JsonObject(), "testSchema")).blockingAwait();

		provider.createIndex(new IndexInfo("testindex", new JsonObject(), new JsonObject(), "testSchema")).blockingAwait();

		provider.deleteIndex("testindex").blockingAwait();

		// provider.validateCreateViaTemplate(new IndexInfo("test", new JsonObject(), new JsonObject())).blockingAwait();

	}

	@Test
	public void testVersion() {
		ElasticSearchProvider provider = getProvider();
		assertEquals("6.8.1", provider.getVersion(true));
	}

	@Test
	public void testPrefixHandling() {
		assertEquals("mesh-", getProvider().installationPrefix());

		String fullIndex = "mesh-node-blar";
		assertEquals("node-blar", getProvider().removePrefix(fullIndex));
	}

	/**
	 * Assert that the indices in the request path never exceed 4096 bytes. Otherwise the request would fail.
	 */
	@Test
	public void testIndexSegmentation() {
		ElasticSearchProvider provider = getProvider();
		List<String> indices = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			StringBuilder builder = new StringBuilder();
			builder.append("mesh-test");
			for (int e = 0; e < 4; e++) {
				builder.append(UUIDUtil.randomUUID());
			}
			String name = builder.toString();
			indices.add(name);
			provider.createIndex(new IndexInfo(name, new JsonObject(), new JsonObject(), "testSchema")).blockingAwait();
		}

		String names[] = indices.stream().toArray(String[]::new);
		provider.refreshIndex(names).blockingAwait();
		provider.refreshIndex(names).blockingAwait();
		provider.clear().blockingAwait();
		provider.clear().blockingAwait();
	}

	@Test
	public void testConcurrencyConflictError() {
		ElasticSearchProvider provider = getProvider();
		provider.createIndex(new IndexInfo("test", new JsonObject(), new JsonObject(), "testSchema")).blockingAwait();
		provider.storeDocument("test", "1", new JsonObject().put("value", 0)).blockingAwait();
		Observable.range(1, 2000).flatMapCompletable(i -> provider.updateDocument("test", "1", new JsonObject().put("value", i), false))
			.blockingAwait();
	}

	private JsonObject getPipelineConfig(List<String> fields) {
		JsonObject config = new JsonObject();
		config.put("description", "Extract attachment information");

		JsonArray processors = new JsonArray();
		for (String field : fields) {
			JsonObject processor = new JsonObject();
			JsonObject settings = new JsonObject();
			settings.put("field", field);
			settings.put("target_field", "field." + field);
			settings.put("ignore_missing", true);
			processor.put("attachment", settings);
			processors.add(processor);
		}

		config.put("processors", processors);
		return config;
	}
}
