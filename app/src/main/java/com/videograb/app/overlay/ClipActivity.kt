package com.videograb.app.overlay

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import com.videograb.app.MainActivity

/**
 * اکتیویتی شفاف و کوتاه‌عمر: از اندروید ۱۰ به بعد فقط اپِ «در پیش‌زمینه» می‌تواند
 * کلیپ‌بورد را بخواند، پس حباب این اکتیویتی را باز می‌کند، لینک را می‌خواند و بسته می‌شود.
 */
class ClipActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = text?.let { Regex("https?://\\S+").find(it)?.value }
        if (url != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra("url", url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } else {
            Toast.makeText(this, "اول لینک پست/ویدیو را Copy کنید", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
