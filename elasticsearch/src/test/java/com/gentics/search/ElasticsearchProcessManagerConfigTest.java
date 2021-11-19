package com.gentics.mesh.search;

import com.gentics.mesh.etc.config.search.ElasticSearchOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ElasticsearchProcessManagerConfigTest {

	@Test
	public void getDefaultIndexSettings() {

		//default index config
		JsonObject tokenizer = new JsonObject();
		tokenizer.put("type", "nGram");
		tokenizer.put("min_gram", "3");
		tokenizer.put("max_gram", "3");

		JsonObject trigramsAnalyzer = new JsonObject();
		trigramsAnalyzer.put("tokenizer", "mesh_default_ngram_tokenizer");
		trigramsAnalyzer.put("filter", new JsonArray().add("lowercase"));

		JsonObject analysis = new JsonObject();
		analysis.put("analyzer", new JsonObject().put("trigrams", trigramsAnalyzer));
		analysis.put("tokenizer", new JsonObject().put("mesh_default_ngram_tokenizer", tokenizer));
		JsonObject defaultSettings =  new JsonObject().put("analysis", analysis);

		//default config as string
		String conf = ElasticSearchOptions.DEFAULT_MESH_ELASTICSEARCH_INDEX_SETTINGS;

		JsonObject optionsDefaultSettings = new JsonObject(conf);

		assertEquals(defaultSettings, optionsDefaultSettings);
	}
}
