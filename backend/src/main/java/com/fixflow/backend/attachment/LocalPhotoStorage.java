package com.fixflow.backend.attachment;

import java.nio.file.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="fixflow.photos.provider",havingValue="local")
public class LocalPhotoStorage implements PhotoStorage {
    private final PhotoSettings settings;
    public LocalPhotoStorage(PhotoSettings settings) { this.settings=settings; }
    public String provider() { return "LOCAL"; }
    private Path path(String key) throws IOException {
        if(!key.matches("[0-9a-f]{32}\\.(png|jpeg)" ) || settings.getLocalRoot().isBlank()) throw PhotoFailure.storage();
        Path root=Path.of(settings.getLocalRoot()).toAbsolutePath().normalize();
        Path component=root.getRoot();
        for(Path part:root) { component=component.resolve(part);if(Files.isSymbolicLink(component)) throw PhotoFailure.storage(); }
        Files.createDirectories(root);
        if(!root.toRealPath().equals(root)) throw PhotoFailure.storage();
        Path target=root.resolve(key);if(Files.isSymbolicLink(target)) throw PhotoFailure.storage();return target;
    }
    public void put(String key,byte[] bytes,String mime) {
        Path target=null;boolean created=false;
        try {target=path(key);try(var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {created=true;out.write(bytes);}}
        catch(IOException|RuntimeException e) {if(created)try{Files.deleteIfExists(target);}catch(IOException ignored){}throw PhotoFailure.storage();}
    }
    public byte[] get(String key) {
        try(var input=Files.newInputStream(path(key),LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(settings.getMaxBytes()+1);if(bytes.length>settings.getMaxBytes()) throw PhotoFailure.storage();return bytes;
        } catch(IOException|RuntimeException e) {throw PhotoFailure.storage();}
    }
    public void delete(String key) {try {Files.deleteIfExists(path(key));}catch(IOException|RuntimeException e){throw PhotoFailure.storage();}}
}
