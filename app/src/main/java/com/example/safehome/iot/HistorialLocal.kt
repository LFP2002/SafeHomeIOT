package com.example.safehome.iot

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "eventos", indices = [Index(value = ["uid", "hogar", "ocurridoEn"])])
data class EventoLocal(@PrimaryKey val id: String, val uid: String, val hogar: String,
    val ocurridoEn: Long, val tipo: String, val origen: String)

@Dao
interface EventosDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertar(evento: EventoLocal)
    @Query("SELECT * FROM eventos WHERE uid = :uid AND hogar = :hogar ORDER BY ocurridoEn DESC, id DESC")
    fun observar(uid: String, hogar: String): Flow<List<EventoLocal>>
}

@Database(entities = [EventoLocal::class], version = 1, exportSchema = false)
abstract class HistorialLocal : RoomDatabase() {
    abstract fun eventos(): EventosDao
    companion object {
        fun abrir(context: Context): HistorialLocal = Room.databaseBuilder(context.applicationContext,
            HistorialLocal::class.java, "safehome-eventos-mqtt.db").build()
    }
}
