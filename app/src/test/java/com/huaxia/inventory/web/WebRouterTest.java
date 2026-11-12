package com.huaxia.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class WebRouterTest {

    private static WebRequest req(String method, String path) {
        return new WebRequest(method, path, Collections.emptyMap(),
                Collections.emptyMap(), new byte[0]);
    }

    @Test
    public void dispatch_matchesExactPath() throws Exception {
        WebRouter router = new WebRouter();
        router.get("/api/ping", r -> WebResponse.ok(Collections.singletonMap("pong", true)));

        WebResponse res = router.dispatch(req("GET", "/api/ping"));

        assertThat(res.status).isEqualTo(200);
        assertThat(new String(res.bytes, "UTF-8")).contains("\"pong\":true");
    }

    @Test
    public void dispatch_extractsPathParam() throws Exception {
        WebRouter router = new WebRouter();
        router.get("/api/tasks/{id}", r -> WebResponse.ok(r.pathParam("id")));

        WebResponse res = router.dispatch(req("GET", "/api/tasks/42"));

        assertThat(new String(res.bytes, "UTF-8")).isEqualTo("\"42\"");
    }

    @Test
    public void dispatch_returns405WhenMethodMismatch() {
        WebRouter router = new WebRouter();
        router.get("/api/tasks", r -> WebResponse.ok("x"));

        WebResponse res = router.dispatch(req("POST", "/api/tasks"));

        assertThat(res.status).isEqualTo(405);
    }

    @Test
    public void dispatch_returns404ForUnknownPath() {
        WebRouter router = new WebRouter();
        WebResponse res = router.dispatch(req("GET", "/api/nope"));
        assertThat(res.status).isEqualTo(404);
    }

    @Test
    public void dispatch_parsesQueryParameters() throws Exception {
        WebRouter router = new WebRouter();
        router.get("/api/assets", r -> WebResponse.ok(r.query("keyword")));

        Map<String, String> q = new HashMap<>();
        q.put("keyword", "笔记本");
        WebRequest request = new WebRequest("GET", "/api/assets", q,
                Collections.emptyMap(), new byte[0]);
        WebResponse res = router.dispatch(request);

        assertThat(new String(res.bytes, "UTF-8")).contains("笔记本");
    }

    @Test
    public void dispatch_matchesParamWithSuffix() throws Exception {
        WebRouter router = new WebRouter();
        router.get("/api/export/task/{id}.xlsx", r -> WebResponse.ok(r.pathParam("id")));

        WebResponse res = router.dispatch(req("GET", "/api/export/task/42.xlsx"));

        assertThat(res.status).isEqualTo(200);
        assertThat(new String(res.bytes, "UTF-8")).isEqualTo("\"42\"");
    }

    @Test
    public void dispatch_requiresSuffixToMatch() {
        WebRouter router = new WebRouter();
        router.get("/api/export/task/{id}.xlsx", r -> WebResponse.ok("x"));

        assertThat(router.dispatch(req("GET", "/api/export/task/42")).status).isEqualTo(404);
        assertThat(router.dispatch(req("GET", "/api/export/task/.xlsx")).status).isEqualTo(404);
    }
}
