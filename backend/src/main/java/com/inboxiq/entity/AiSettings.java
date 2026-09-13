package com.inboxiq.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * The administrator's app-wide AI provider choice — a single row. See
 * {@code AiSettingsService} for how it combines with the AI_* environment
 * variables. The API key is only ever held here encrypted.
 */
@Entity
@Table(name = "ai_settings")
@Getter
@Setter
@NoArgsConstructor
public class AiSettings {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id = SINGLETON_ID;

    /** An {@code AiProvider} name, kept as text so a retired provider can't break loading. */
    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    /** Only used by the CUSTOM provider; presets have fixed endpoints. */
    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    /** Encrypted with TokenEncryptionService; null means "use the environment's key". */
    @Column(name = "encrypted_api_key")
    private String encryptedApiKey;

    @Column(name = "updated_by", length = 320)
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public String toString() {
        return "AiSettings[provider=" + provider + ", model=" + model + "]";
    }
}
