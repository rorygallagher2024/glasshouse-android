package org.glasshouse.android

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors

/** The saved TVs: open one, add one by QR code or address, edit or remove. */
class MainActivity : AppCompatActivity() {

    private lateinit var store: TvStore
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private var adapter: TvAdapter? = null

    private enum class Reach { CHECKING, ONLINE, OFFLINE }

    /**
     * Last probe result by origin, not id, so an edited address starts over
     * as unknown. Read and written on the main thread only.
     */
    private val reach = mutableMapOf<String, Reach>()
    private val inFlight = mutableSetOf<String>()
    private val probes = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** Rechecks while the list is on screen, so a TV turned on or off shows it. */
    private val recheck = object : Runnable {
        override fun run() {
            checkAll()
            main.postDelayed(this, RECHECK_MS)
        }
    }

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val link = TvLink.parse(contents)
        if (link == null) {
            Toast.makeText(this, R.string.scan_not_tv, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        // A TV already saved is matched by address: scanning it again is how a
        // changed token gets in.
        val known = store.byOrigin(link.origin)
        if (known != null) {
            store.save(known.copy(link = if (link.token.isEmpty()) known.link else link))
            refresh()
            Toast.makeText(this, getString(R.string.tv_updated, known.name), Toast.LENGTH_SHORT).show()
        } else {
            editTv(null, link)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        paintSystemBars()
        super.onCreate(savedInstanceState)
        if (!IntroActivity.seen(this)) {
            startActivity(Intent(this, IntroActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        padForSystemBars(findViewById(R.id.appbar), findViewById(R.id.content))

        store = TvStore(this)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.layoutManager = LinearLayoutManager(this)
        findViewById<View>(R.id.add).setOnClickListener { chooseHowToAdd() }
    }

    override fun onResume() {
        super.onResume()
        if (!::store.isInitialized) return
        refresh()
        main.postDelayed(recheck, RECHECK_MS)
    }

    override fun onPause() {
        main.removeCallbacks(recheck)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(recheck)
        probes.shutdownNow()
        super.onDestroy()
    }

    private fun checkAll() {
        for (tv in store.all()) {
            val link = tv.link
            // A probe gives up within 8 seconds, but one stuck in DNS can outlast a round.
            if (!inFlight.add(link.origin)) continue
            probes.execute {
                val answers = TvProbe.answers(link)
                main.post {
                    inFlight.remove(link.origin)
                    if (isDestroyed) return@post
                    reach[link.origin] = if (answers) Reach.ONLINE else Reach.OFFLINE
                    adapter?.statusChanged(link.origin)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_about) {
            startActivity(Intent(this, AboutActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val tvs = store.all()
        adapter = TvAdapter(tvs).also { list.adapter = it }
        // Covers a TV just added or edited; already-running probes are skipped.
        checkAll()
        empty.visibility = if (tvs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun chooseHowToAdd() {
        val choices = arrayOf(getString(R.string.tv_add_scan), getString(R.string.tv_add_manual))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tv_add)
            .setItems(choices) { _, which ->
                if (which == 0) startScan() else editTv(null, null)
            }
            .show()
    }

    private fun startScan() {
        scan.launch(ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.scan_prompt))
            .setBeepEnabled(false))
    }

    /** Edits [tv], or adds a new TV prefilled from [scanned] when [tv] is null. */
    private fun editTv(tv: Tv?, scanned: TvLink?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_tv, null)
        val name = view.findViewById<TextInputEditText>(R.id.name)
        val address = view.findViewById<TextInputEditText>(R.id.address)
        val addressLayout = view.findViewById<TextInputLayout>(R.id.address_layout)
        val token = view.findViewById<TextInputEditText>(R.id.token)

        val start = tv?.link ?: scanned
        name.setText(tv?.name ?: start?.let { getString(R.string.tv_default_name, it.label) } ?: "")
        address.setText(start?.label ?: "")
        token.setText(start?.token ?: "")

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (tv == null) R.string.tv_add else R.string.tv_edit)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        // Set after show() so a bad address keeps the dialog open.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // The scheme shown is dropped from the field, so an https
                // origin is put back rather than reparsed as http.
                val typed = address.text.toString().trim()
                val parsed = TvLink.parse(if (start != null && typed == start.label) start.origin else typed)
                if (parsed == null) {
                    addressLayout.error = getString(R.string.tv_address_bad)
                    return@setOnClickListener
                }
                val link = TvLink(parsed.origin, token.text.toString().trim().ifEmpty { parsed.token })
                val label = name.text.toString().trim().ifEmpty { getString(R.string.tv_default_name, link.label) }
                store.save(Tv(tv?.id ?: TvStore.newId(), label, link))
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmRemove(tv: Tv) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.tv_remove_confirm, tv.name))
            .setPositiveButton(R.string.tv_remove) { _, _ ->
                store.remove(tv.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun open(tv: Tv) {
        startActivity(Intent(this, DashboardActivity::class.java)
            .putExtra(DashboardActivity.EXTRA_TV_ID, tv.id))
    }

    private inner class TvAdapter(private val tvs: List<Tv>) : RecyclerView.Adapter<TvHolder>() {
        override fun getItemCount() = tvs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            TvHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_tv, parent, false))

        override fun onBindViewHolder(holder: TvHolder, position: Int) = holder.bind(tvs[position])

        fun statusChanged(origin: String) {
            tvs.forEachIndexed { i, tv -> if (tv.link.origin == origin) notifyItemChanged(i) }
        }
    }

    private inner class TvHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.name)
        private val address = view.findViewById<TextView>(R.id.address)
        private val more = view.findViewById<ImageButton>(R.id.more)

        fun bind(tv: Tv) {
            name.text = tv.name
            val state = reach[tv.link.origin] ?: Reach.CHECKING
            val (label, dot) = when (state) {
                Reach.ONLINE -> R.string.tv_online to R.drawable.dot_online
                Reach.OFFLINE -> R.string.tv_offline to R.drawable.dot_offline
                Reach.CHECKING -> R.string.tv_checking to R.drawable.dot_checking
            }
            address.text = getString(R.string.tv_status_line, getString(label), tv.link.label)
            address.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
            itemView.setOnClickListener { open(tv) }
            more.contentDescription = getString(R.string.tv_more, tv.name)
            more.setOnClickListener {
                val menu = PopupMenu(this@MainActivity, more)
                menu.inflate(R.menu.tv_item)
                menu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_edit -> editTv(tv, null)
                        R.id.action_remove -> confirmRemove(tv)
                    }
                    true
                }
                menu.show()
            }
        }
    }

    companion object {
        private const val RECHECK_MS = 15_000L
    }
}
