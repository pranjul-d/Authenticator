package com.softradix.authenticatordemo.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Token::class], version = 1)
abstract class TokenDatabase : RoomDatabase() {
    abstract fun tokenDao(): TokenDao

}
