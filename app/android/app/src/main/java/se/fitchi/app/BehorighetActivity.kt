package se.fitchi.app

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Visas när någon trycker på "Läs mer" vid Fitchis behörigheter i Health Connect. */
class BehorighetActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(56, 96, 56, 56)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            text = """
                Fitchi och din hälsodata

                Fitchi läser dina steg, din sömn, din puls och dina träningspass från Health Connect, så att din fitchi kan spegla hur du mår utan att du behöver logga något.

                • Datan stannar i din telefon. Den skickas inte till någon server.
                • Den används bara för spelet: humör, glöd, XP och uppdrag.
                • Den används aldrig till reklam och delas aldrig med någon.
                • Du kan när som helst ta bort Fitchis behörigheter i Health Connect.

                Det här är en testversion av Fitchi.
            """.trimIndent()
        }
        val rull = ScrollView(this).apply { setBackgroundColor(0xFF120C1C.toInt()); addView(text) }
        setContentView(rull)
    }
}
