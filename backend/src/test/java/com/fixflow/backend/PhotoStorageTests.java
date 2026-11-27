package com.fixflow.backend;

import com.fixflow.backend.attachment.*;
import java.io.*;
import java.net.http.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PhotoStorageTests {
    @TempDir Path root;
    private static final String KEY="0123456789abcdef0123456789abcdef.png";
    private PhotoSettings settings(){var s=new PhotoSettings();s.setLocalRoot(root.toString());s.setCloudName("test-cloud");s.setApiKey("test-key");s.setApiSecret("abcd");return s;}
    @Test void localKeysCannotEscapeAndCollisionsCannotOverwriteExistingFiles()throws Exception{
        var storage=new LocalPhotoStorage(settings());byte[] original={1,2,3};storage.put(KEY,original,"image/png");
        assertThrows(PhotoFailure.class,()->storage.put(KEY,new byte[]{9},"image/png"));assertArrayEquals(original,storage.get(KEY));
        for(String key:List.of("../outside.png","/etc/passwd","C:\\outside.png","file.svg"))assertThrows(PhotoFailure.class,()->storage.get(key));
        storage.delete(KEY);assertFalse(Files.exists(root.resolve(KEY)));
    }
    @Test void symbolicLinkRootIsRejectedBeforeAnyPhotoWrite()throws Exception{
        var storage=new LocalPhotoStorage(settings());
        try(var files=mockStatic(Files.class,CALLS_REAL_METHODS)){
            files.when(()->Files.isSymbolicLink(root)).thenReturn(true);
            assertThrows(PhotoFailure.class,()->storage.put(KEY,new byte[]{1},"image/png"));
        }
        assertFalse(Files.exists(root.resolve(KEY)));
    }
    @Test void signedUploadSignatureUsesSortedParametersAndSha256(){
        var storage=new CloudinaryPhotoStorage(settings(),mock(HttpClient.class),new ObjectMapper());
        assertEquals("c38a1d90923b331303b7d9c1a98cbabb42d7eb046aeddaaba7c08b267a6c4a16",storage.signature(Map.of("timestamp","1315060076","public_id","sample_image")));
    }
    @SuppressWarnings("unchecked")
    @Test void cloudinaryUploadIsAuthenticatedAndDownloadNeverReturnsProviderUrls()throws Exception{
        HttpClient client=mock(HttpClient.class);HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenAnswer(inv->new ByteArrayInputStream("{\"public_id\":\"fixflow/0123456789abcdef0123456789abcdef\",\"type\":\"authenticated\",\"resource_type\":\"image\"}".getBytes()));
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var configured=settings();configured.setApiSecret("private-test-secret-value");
        var storage=new CloudinaryPhotoStorage(configured,client,new ObjectMapper());storage.put(KEY,new byte[]{1,2,3},"image/png");
        var capture=ArgumentCaptor.forClass(HttpRequest.class);verify(client).send(capture.capture(),any(HttpResponse.BodyHandler.class));
        var request=capture.getValue();assertEquals("api.cloudinary.com",request.uri().getHost());assertTrue(request.uri().getPath().endsWith("/image/upload"));
        String multipart=new String(requestBody(request),java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(multipart.contains("authenticated"));assertTrue(multipart.contains("overwrite"));assertFalse(multipart.contains(configured.getApiSecret()));
        reset(client);when(response.body()).thenAnswer(inv->new ByteArrayInputStream(new byte[]{1,2,3}));when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertArrayEquals(new byte[]{1,2,3},storage.get(KEY));verify(client).send(capture.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("res.cloudinary.com",capture.getValue().uri().getHost());assertTrue(capture.getValue().uri().getPath().contains("/image/authenticated/s--"));
    }
    @SuppressWarnings("unchecked")
    @Test void cloudinaryRejectsRedirectsProviderFailuresAndUnsafeKeys()throws Exception{
        HttpClient client=mock(HttpClient.class);HttpResponse<InputStream> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(302);when(response.body()).thenAnswer(inv->new ByteArrayInputStream(new byte[0]));
        when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var storage=new CloudinaryPhotoStorage(settings(),client,new ObjectMapper());
        assertThrows(PhotoFailure.class,()->storage.get(KEY));assertThrows(PhotoFailure.class,()->storage.get("../../other.png"));
        reset(client);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("secret upstream URI"));
        assertEquals("Photo storage is unavailable. Please retry.",assertThrows(PhotoFailure.class,()->storage.get(KEY)).getMessage());
    }
    private byte[] requestBody(HttpRequest request)throws Exception{
        var output=new ByteArrayOutputStream();var complete=new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer bytes){byte[] chunk=new byte[bytes.remaining()];bytes.get(chunk);output.writeBytes(chunk);}
            public void onError(Throwable error){complete.completeExceptionally(error);}
            public void onComplete(){complete.complete(output.toByteArray());}
        });return complete.get(3,TimeUnit.SECONDS);
    }
}
