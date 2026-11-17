package com.example.ragbot.domain;

/** 文档处理状态机：PENDING -> PARSING -> CHUNKING -> EMBEDDING -> READY / FAILED */
public enum DocumentStatus {
    PENDING,
    PARSING,
    CHUNKING,
    EMBEDDING,
    READY,
    FAILED
}
