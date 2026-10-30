package com.neethu.gouxiang.chat

import com.neethu.gouxiang.data.MemoryEntity

/**
 * 单测假件:[MemorySource] 的进程内实现(零 Android 依赖)。
 * MemoryBook/Coordinator/Extractor 的全部读写经 MemorySource 走,
 * 存储换内存,业务语义与生产同代码路径。
 */
class FakeMemorySource(initial: List<MemoryEntity> = emptyList()) : MemorySource {

    val rows = initial.toMutableList()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    override suspend fun all(characterId: Long): List<MemoryEntity> =
        rows.filter { it.characterId == characterId }.sortedByDescending { it.importance }

    override suspend fun update(memory: MemoryEntity) {
        val idx = rows.indexOfFirst { it.id == memory.id }
        if (idx >= 0) rows[idx] = memory
    }

    override suspend fun insert(memory: MemoryEntity): Long {
        val withId = memory.copy(id = if (memory.id == 0L) nextId++ else memory.id)
        rows += withId
        return withId.id
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteSince(characterId: Long, categories: Set<String>, sinceMs: Long): Int {
        // 与 Room SQL 同语义:该角色 + 分类命中 + at >= sinceMs 的行删除,返回条数。
        val doomed = rows.filter {
            it.characterId == characterId && it.category in categories && it.at >= sinceMs
        }
        rows.removeAll(doomed)
        return doomed.size
    }
}
