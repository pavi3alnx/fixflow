package com.fixflow.backend.attachment;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name="fixflow.photos.provider",havingValue="cloudinary",matchIfMissing=true)
public class CloudinaryPhotoStorage implements PhotoStorage {
    private final PhotoSettings settings;
    private final HttpClient client;
    private final ObjectMapper json;
    public CloudinaryPhotoStorage(PhotoSettings settings,@Qualifier("photoHttpClient") HttpClient client,ObjectMapper json) { this.settings=settings;this.client=client;this.json=json; }
    public String provider() { return "CLOUDINARY"; }
    private String publicId(String key) {
        if(!key.matches("[0-9a-f]{32}\\.(png|jpeg)") || !settings.getCloudName().matches("[A-Za-z0-9_-]+") || settings.getApiKey().isBlank() || settings.getApiSecret().isBlank()) throw PhotoFailure.storage();
        return "fixflow/"+key.substring(0,key.lastIndexOf('.'));
    }
    public String signature(Map<String,String> parameters) {
        String canonical=new TreeMap<>(parameters).entrySet().stream().map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("&"));
        return HexFormat.of().formatHex(digest("SHA-256",canonical+settings.getApiSecret()));
    }
    private byte[] digest(String algorithm,String value) {
        try {return MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));}
        catch(java.security.GeneralSecurityException e){throw PhotoFailure.storage();}
    }
    private Map<String,String> params(String key) {
        return new TreeMap<>(Map.of("public_id",publicId(key),"type","authenticated","timestamp",Long.toString(Instant.now().getEpochSecond())));
    }
    public void put(String key,byte[] bytes,String mediaType) {
        var values=params(key);values.put("overwrite","false");values.put("format",key.substring(key.lastIndexOf('.')+1));
        String boundary="FixFlow"+UUID.randomUUID().toString().replace("-","");
        values.put("signature",signature(values));values.put("api_key",settings.getApiKey());
        try {
            var out=new ByteArrayOutputStream();
            for(var entry:values.entrySet()) out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+entry.getKey()+"\"\r\n\r\n"+entry.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo\"\r\nContent-Type: "+mediaType+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] response=send(api("upload"),"multipart/form-data; boundary="+boundary,out.toByteArray(),65536);
            var result=json.readTree(response);
            if(!publicId(key).equals(result.path("public_id").asText()) || !"authenticated".equals(result.path("type").asText()) || !"image".equals(result.path("resource_type").asText())) throw PhotoFailure.storage();
        } catch(IOException|RuntimeException e) {throw PhotoFailure.storage();}
    }
    private URI api(String action) {return URI.create("https://api.cloudinary.com/v1_1/"+settings.getCloudName()+"/image/"+action);}
    public byte[] get(String key) {
        String path=publicId(key)+key.substring(key.lastIndexOf('.'));
        String signed=Base64.getUrlEncoder().withoutPadding().encodeToString(digest("SHA-256",path+settings.getApiSecret())).substring(0,8);
        URI uri=URI.create("https://res.cloudinary.com/"+settings.getCloudName()+"/image/authenticated/s--"+signed+"--/"+path);
        return send(uri,null,null,settings.getMaxBytes());
    }
    public void delete(String key) {
        var values=params(key);values.put("invalidate","true");values.put("signature",signature(values));values.put("api_key",settings.getApiKey());
        String body=values.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
        send(api("destroy"),"application/x-www-form-urlencoded",body.getBytes(StandardCharsets.UTF_8),65536);
    }
    private String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private byte[] send(URI uri,String contentType,byte[] data,int maximum) {
        try {
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
            if(data==null) request.GET();else request.header("Content-Type",contentType).POST(HttpRequest.BodyPublishers.ofByteArray(data));
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()<200 || response.statusCode()>=300) throw PhotoFailure.storage();
                byte[] bytes=stream.readNBytes(maximum+1);if(bytes.length>maximum) throw PhotoFailure.storage();return bytes;
            }
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw PhotoFailure.storage();}
        catch(IOException|RuntimeException e){throw PhotoFailure.storage();}
    }
}
