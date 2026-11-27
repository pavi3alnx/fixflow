package com.fixflow.backend.attachment;

import java.io.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PhotoValidator {
    public record Image(byte[] bytes,String format,String mediaType,String filename) {
        @Override public String toString() { return "Photo[redacted]"; }
    }
    private final PhotoSettings settings;
    public PhotoValidator(PhotoSettings settings) { this.settings=settings; }
    public Image validate(MultipartFile file) {
        if(file.isEmpty()) throw PhotoFailure.invalid();
        if(file.getSize()>settings.getMaxBytes()) throw oversized();
        try(var input=file.getInputStream()) {
            byte[] bytes=input.readNBytes(settings.getMaxBytes()+1);
            if(bytes.length>settings.getMaxBytes()) throw oversized();
            boolean png=bytes.length>=8 && bytes[0]==(byte)137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71 && bytes[4]==13 && bytes[5]==10 && bytes[6]==26 && bytes[7]==10;
            boolean jpg=bytes.length>=3 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255;
            if(!png && !jpg) throw PhotoFailure.invalid();
            String format=png?"png":"jpeg",mime=png?"image/png":"image/jpeg";
            if(!mime.equalsIgnoreCase(file.getContentType())) throw PhotoFailure.invalid();
            try(var images=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(images);if(!readers.hasNext()) throw PhotoFailure.invalid();
                var reader=readers.next();
                try {
                    reader.setInput(images,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1 || height<1 || width>settings.getMaxDimension() || height>settings.getMaxDimension() || (long)width*height>settings.getMaxPixels())
                        throw new PhotoFailure(HttpStatus.PAYLOAD_TOO_LARGE,"Photo dimensions exceed the configured limit.");
                    var decoded=reader.read(0);if(decoded==null) throw PhotoFailure.invalid();
                    var output=new ByteArrayOutputStream();
                    try { if(!ImageIO.write(decoded,format,output)) throw PhotoFailure.invalid(); } finally { decoded.flush(); }
                    if(output.size()>settings.getMaxBytes()) throw oversized();
                    String name=file.getOriginalFilename();name=name==null?"photo":name.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[^A-Za-z0-9._-]","_");
                    if(name.isBlank()) name="photo";if(name.length()>120) name=name.substring(0,120);
                    return new Image(output.toByteArray(),format,mime,name);
                } finally {reader.dispose();}
            }
        } catch(PhotoFailure e) { throw e; } catch(IOException|RuntimeException e) { throw PhotoFailure.invalid(); }
    }
    private PhotoFailure oversized() { return new PhotoFailure(HttpStatus.PAYLOAD_TOO_LARGE,"Photo exceeds the configured file-size limit."); }
}
