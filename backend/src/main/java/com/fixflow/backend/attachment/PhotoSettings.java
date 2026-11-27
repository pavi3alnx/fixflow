package com.fixflow.backend.attachment;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter @Setter @Validated @ConfigurationProperties("fixflow.photos")
public class PhotoSettings {
    @Pattern(regexp="local|cloudinary") private String provider="cloudinary";
    private String localRoot="";
    @Min(1) @Max(20971520) private int maxBytes=5242880;
    @Min(1) @Max(100) private int maxFiles=20;
    @Min(1) @Max(10000) private int maxDimension=6000;
    @Min(1) @Max(25000000) private long maxPixels=12000000;
    private String cloudName="";
    private String apiKey="";
    private String apiSecret="";
}
