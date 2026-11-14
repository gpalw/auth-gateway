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
    record Call(String model,int inputBound,int outputBound,byte[] body,String endpoint,boolean stream) {}
    Call chat(JsonNode root) {
        allowed(root,Set.of("model","messages","max_completion_tokens","max_tokens","stream","stream_options","temperature","top_p","n","store"));
        String model=text(root,"model");
        RelaySettings.Price price=settings.price(model);
        if (root.has("stream") && !root.path("stream").isBoolean()) throw new RelayError(400,"invalid_stream");
        boolean stream=root.path("stream").asBoolean();
        if(root.has("stream_options")){allowed(root.path("stream_options"),Set.of("include_usage"));if(!stream || root.path("stream_options").has("include_usage") && !root.path("stream_options").path("include_usage").isBoolean())throw new RelayError(400,"invalid_stream_options");}
        if(root.has("n") && (!root.path("n").isIntegralNumber() || root.path("n").asInt()!=1))throw new RelayError(400,"unsupported_n");
        noStorage(root);
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
        sampling(root);
        ObjectNode body=(ObjectNode)root.deepCopy();
        body.remove("max_tokens"); body.put("max_completion_tokens",output); body.put("stream",stream);body.put("store",false);
        if(stream)body.putObject("stream_options").put("include_usage",true);
        // Reserve the entire configured input ceiling, not a heuristic tokenizer estimate.
        return new Call(model,price.maxInput(),output,json.writeValueAsBytes(body),"/chat/completions",stream);
    }
    Call responses(JsonNode root) {
        allowed(root,Set.of("model","input","instructions","max_output_tokens","stream","temperature","top_p","store"));
        String model=text(root,"model");RelaySettings.Price price=settings.price(model);noStorage(root);sampling(root);
        if(root.has("stream") && !root.path("stream").isBoolean())throw new RelayError(400,"invalid_stream");
        int output=price.maxOutput();
        if(root.has("max_output_tokens")){
            if(!root.path("max_output_tokens").isIntegralNumber() || !root.path("max_output_tokens").canConvertToInt())throw new RelayError(400,"invalid_output_limit");output=root.path("max_output_tokens").asInt();
        }
        if(output<1 || output>price.maxOutput())throw new RelayError(400,"invalid_output_limit");
        long bytes=1024;
        if(root.has("instructions"))bytes+=text(root,"instructions").getBytes(StandardCharsets.UTF_8).length+64;
        JsonNode input=root.path("input");
        if(input.isString())bytes+=input.asString().getBytes(StandardCharsets.UTF_8).length;
        else if(input.isArray() && !input.isEmpty() && input.size()<=100){
            for(JsonNode item:input){
                allowed(item,Set.of("role","content","type"));
                if(item.has("type") && !text(item,"type").equals("message"))throw new RelayError(400,"unsupported_input_type");
                if(!Set.of("system","developer","user","assistant").contains(text(item,"role")))throw new RelayError(400,"invalid_role");
                JsonNode content=item.path("content");bytes+=64;
                if(content.isString())bytes+=content.asString().getBytes(StandardCharsets.UTF_8).length;
                else if(content.isArray() && !content.isEmpty() && content.size()<=100){
                    for(JsonNode part:content){allowed(part,Set.of("type","text"));if(!text(part,"type").equals("input_text"))throw new RelayError(400,"unsupported_content_type");bytes+=text(part,"text").getBytes(StandardCharsets.UTF_8).length+16;}
                }else throw new RelayError(400,"invalid_content");
            }
        }else throw new RelayError(400,"invalid_input");
        if(bytes>price.maxInput())throw new RelayError(400,"input_limit_exceeded");
        ObjectNode body=(ObjectNode)root.deepCopy();boolean stream=root.path("stream").asBoolean();
        body.put("max_output_tokens",output);body.put("stream",stream);body.put("store",false);
        return new Call(model,price.maxInput(),output,json.writeValueAsBytes(body),"/responses",stream);
    }
    private static void noStorage(JsonNode root){if(root.has("store") && (!root.path("store").isBoolean() || root.path("store").asBoolean()))throw new RelayError(400,"storage_not_supported");}
    private static void sampling(JsonNode root){
        for(String param:List.of("temperature","top_p"))if(root.has(param) && (!root.path(param).isNumber()
            || !Double.isFinite(root.path(param).asDouble()) || root.path(param).asDouble()<0
            || root.path(param).asDouble()>(param.equals("top_p")?1:2)))throw new RelayError(400,"invalid_sampling");
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
