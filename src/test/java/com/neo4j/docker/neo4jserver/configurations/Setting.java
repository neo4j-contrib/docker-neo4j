package com.neo4j.docker.neo4jserver.configurations;

public enum Setting
{
    APOC_EXPORT_FILE_ENABLED,
    BACKUP_ENABLED,
    BACKUP_LISTEN_ADDRESS,
    CLUSTER_DISCOVERY_ADDRESS,
    CLUSTER_RAFT_ADDRESS,
    CLUSTER_TRANSACTION_ADDRESS,
    DEFAULT_LISTEN_ADDRESS,
    DIRECTORIES_DATA,
    DIRECTORIES_LOGS,
    DIRECTORIES_METRICS,
    JVM_ADDITIONAL,
    LOGS_GC_ROTATION_KEEPNUMBER,
    MEMORY_HEAP_INITIALSIZE,
    MEMORY_HEAP_MAXSIZE,
    MEMORY_PAGECACHE_SIZE,
    SECURITY_PROCEDURES_UNRESTRICTED,
    TXLOG_RETENTION_POLICY,
    HTTP_LISTEN_ADDRESS
}
