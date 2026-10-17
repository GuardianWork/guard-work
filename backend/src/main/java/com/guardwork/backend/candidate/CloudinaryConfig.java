package com.guardwork.backend.candidate;

import com.cloudinary.Cloudinary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class CloudinaryConfig {

    @Value("${cloudinary.cloud_name:test_cloud}")
    private String cloudName;

    @Value("${cloudinary.api_key:test-key}")
    private String apiKey;

    @Value("${cloudinary.api_secret:test-api}")
    private String apiSecret;

    @Bean
    @Lazy // bean will be instantiated only when first needed
    public Cloudinary cloudinary() {
        Map<String, String> config = new HashMap<>();
        config.put("cloud_name", cloudName);
        config.put("api_key", apiKey);
        config.put("api_secret", apiSecret);
        return new Cloudinary(config);
    }
}
