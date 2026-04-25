package io.quillloom.infrastructure.preprocess;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.quillloom.domain.knowledge.KnowledgeCardType;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearxngKnowledgeSearchClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldMapSearxngJsonResultsIntoKnowledgeSearchHits() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", new JsonHandler("""
                {
                  \"query\": \"犬小哈教程\",
                  \"number_of_results\": 2,
                  \"results\": [
                    {
                      \"url\": \"https://moedog.org/guestbook.html\",
                      \"title\": \"留言板 - 犬's Blog\",
                      \"content\": \"这里是用来灌水的地方。\",
                      \"engine\": \"google\",
                      \"engines\": [\"google\"],
                      \"score\": 1,
                      \"category\": \"general\"
                    },
                    {
                      \"url\": \"https://example.com/tutorial\",
                      \"title\": \"犬小哈教程合集\",
                      \"content\": \"收录了基础教程与进阶教程。\",
                      \"engine\": \"bing\",
                      \"engines\": [\"bing\", \"duckduckgo\"],
                      \"score\": 0.9,
                      \"category\": \"general\"
                    }
                  ]
                }
                """));
        server.start();

        KnowledgeSearchSearxngProperties properties = new KnowledgeSearchSearxngProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/search");
        properties.setTimeoutSeconds(5);
        properties.setMaxResults(3);
        properties.setFormat("json");

        SearxngKnowledgeSearchClient client = new SearxngKnowledgeSearchClient(new OkHttpClient(), properties);
        List<KnowledgeSearchHit> hits = client.search(new KnowledgeSearchQuery(
                KnowledgeCardType.TERM_EXPLANATION,
                "犬小哈教程",
                List.of("犬小哈", "教程"),
                List.of("犬小哈教程"),
                List.of(),
                "PROJECT"
        ));

        assertEquals(2, hits.size());
        assertEquals("留言板 - 犬's Blog", hits.get(0).title());
        assertEquals("这里是用来灌水的地方。", hits.get(0).snippet());
        assertEquals("https://moedog.org/guestbook.html", hits.get(0).url());
        assertEquals("google", hits.get(0).source());
        assertTrue(hits.get(1).source().contains("bing"));
    }

    @Test
    void shouldAppendExpectedSearxngQueryParameters() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            assertTrue(query.contains("q=%E7%8A%AC%E5%B0%8F%E5%93%88%E6%95%99%E7%A8%8B"));
            assertTrue(query.contains("format=json"));
            assertTrue(query.contains("language=zh-CN"));
            assertTrue(query.contains("engines=google%2Cbing"));
            assertTrue(query.contains("categories=general"));
            assertTrue(query.contains("pageno=1"));
            byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        });
        server.start();

        KnowledgeSearchSearxngProperties properties = new KnowledgeSearchSearxngProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/search");
        properties.setTimeoutSeconds(5);
        properties.setFormat("json");
        properties.setLanguage("zh-CN");
        properties.setEngines(List.of("google", "bing"));
        properties.setCategories(List.of("general"));

        SearxngKnowledgeSearchClient client = new SearxngKnowledgeSearchClient(new OkHttpClient(), properties);
        List<KnowledgeSearchHit> hits = client.search(new KnowledgeSearchQuery(
                KnowledgeCardType.TERM_EXPLANATION,
                "犬小哈教程",
                List.of(),
                List.of(),
                List.of(),
                "PROJECT"
        ));

        assertFalse(hits.iterator().hasNext());
    }

    private static final class JsonHandler implements HttpHandler {
        private final String body;

        private JsonHandler(String body) {
            this.body = body;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        }
    }
}