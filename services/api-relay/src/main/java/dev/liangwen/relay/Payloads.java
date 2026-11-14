package dev.liangwen.relay;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

@Component
final class Payloads {
    private final RelaySettings settings;
    private final ObjectMapper json;
    Payloads(RelaySettings settings,ObjectMapper json) { this.settings=settings; this.json=json; }
    record Call(String model,int inputBound,int outputBound,byte[] body) {}
    Call chat(JsonNode root) {
        allowed(root,Set.of("model","messages","max_completion_tokens","max_tokens","stream","temperature","top_p"));
        String model=text(root,"model");
        RelaySettings.Price price=settings.price(model);
        if (root.has("stream") && !root.path("stream").isBoolean()) throw new RelayError(400,"invalid_stream");
        if (root.path("stream").asBoolean()) throw new RelayError(400,"stream_not_supported_yet");
        if (root.has("max_completion_tokens") && root.has("max_tokens")) throw new RelayError(400,"ambiguous_output_limit");
        String cap=root.has("max_tokens")?"max_tokens":"max_completion_tokens";
        int output=price.maxOutput();
        if (root.has(cap)) {
            if (!root.path(cap).isIntegralNumber() || !root.path(cap).canConvertToInt()) throw new RelayError(400,"invalid_output_limit");
            output=root.path(cap).asInt();
        }
        if (output<1 || output>price.maxOutput()) throw new RelayError(400,"invalid_output_limit");
        JsonNode messages=root.path("messages");
        if (!messages.isArray() || messages.isEmpty() || messages.size()>100) throw new RelayError(400,"invalid_messages");
        long bytes=1024;
        for (JsonNode m:messages) {
            allowed(m,Set.of("role","content"));
            if (!Set.of("system","developer","user","assistant").contains(text(m,"role"))) throw new RelayError(400,"invalid_role");
            bytes+=text(m,"content").getBytes(StandardCharsets.UTF_8).length+64;
        }
        if (bytes>price.maxInput()) throw new RelayError(400,"input_limit_exceeded");
        for(String param:List.of("temperature","top_p")) if(root.has(param) && (!root.path(param).isNumber()
                || !Double.isFinite(root.path(param).asDouble()) || root.path(param).asDouble()<0
                || root.path(param).asDouble()>(param.equals("top_p")?1:2))) throw new RelayError(400,"invalid_sampling");
        ObjectNode body=(ObjectNode)root.deepCopy();
        body.remove("max_tokens"); body.put("max_completion_tokens",output); body.put("stream",false);
        // Reserve the entire configured input ceiling, not a heuristic tokenizer estimate.
        return new Call(model,price.maxInput(),output,json.writeValueAsBytes(body));
    }
    static void allowed(JsonNode node,Set<String> names) {
        if (!node.isObject()) throw new RelayError(400,"invalid_json_object");
        for(String n:node.propertyNames()) if(!names.contains(n)) throw new RelayError(400,"unsupported_parameter");
    }
    static String text(JsonNode node,String field) {
        if (!node.path(field).isString()) throw new RelayError(400,"invalid_"+field);
        return node.path(field).asString();
    }
}
