package io.github.ian_miller.wuziqi.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

@Entity(tableName = "players")
data class Player(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val avatarUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)