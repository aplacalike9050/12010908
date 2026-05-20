package com.sustech.privacyaiproject.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "developer_account",
        schema = "privacy_ai_schema",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_developer_account_username", columnNames = "username"),
                @UniqueConstraint(name = "uk_developer_account_email", columnNames = "email")
        }
)
public class DeveloperAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 160)
    private String passwordHash;

    @Column(length = 120)
    private String email;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(nullable = false, length = 32)
    private String role = "DEVELOPER";

    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createTime = now;
        updateTime = now;
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }
}
