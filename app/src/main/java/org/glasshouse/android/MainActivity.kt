package org.glasshouse.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
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
    private lateinit var lanBanner: View
    private var adapter: TvAdapter? = null

    private enum class Reach { CHECKING, ON, ASLEEP, ONLINE, OFFLINE, UNTRUSTED_CERT }

    /** A TV's state as shown; [label] is the server's name for a dark TV's state. */
    private data class Shown(val reach: Reach, val label: String? = null)

    /**
     * Last probe result by origin, not id, so an edited address starts over
     * as unknown. Read and written on the main thread only.
     */
    private val shown = mutableMapOf<String, Shown>()
    private val inFlight = mutableSetOf<String>()

    private val probes = Executors.newFixedThreadPool(4)

    /** Searches run apart from the probes: one takes several seconds. */
    private val searches = Executors.newSingleThreadExecutor()
    private var searching = false
    private var lastRefind = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Rechecks while the list is on screen, so a TV turned on or off shows it. */
    private val recheck = object : Runnable {
        override fun run() {
            checkAll()
            main.postDelayed(this, RECHECK_MS)
        }
    }

    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused for good: Android no longer shows the prompt, only Settings can change it.
        if (!granted && !shouldShowRequestPermissionRationale(LocalNetwork.PERMISSION) && bannerTapped) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        bannerTapped = false
        refresh()
    }
    private var bannerTapped = false

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val link = TvLink.parse(contents)
        if (link == null) {
            Toast.makeText(this, R.string.scan_not_tv, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        // A saved TV is matched by address, or failing that by MAC, which is
        // how a TV that has moved to a new address gets its entry updated
        // rather than a second one.
        probes.execute {
            val identity = TvProbe.identify(link)
            main.post {
                if (isDestroyed) return@post
                val known = store.byOrigin(link.origin) ?: identity?.let { store.byMac(it.mac) }
                if (known != null) {
                    val token = link.token.ifEmpty { if (known.link.origin == link.origin) known.link.token else "" }
                    store.save(known.copy(
                        link = TvLink(link.origin, token),
                        mac = identity?.mac ?: known.mac,
                        wakeOnLan = identity?.wakeOnLan ?: known.wakeOnLan,
                    ))
                    refresh()
                    Toast.makeText(this, getString(R.string.tv_updated, known.name), Toast.LENGTH_SHORT).show()
                } else {
                    editTv(null, link, identity)
                }
            }
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
        lanBanner = findViewById(R.id.lan_banner)
        list.layoutManager = LinearLayoutManager(this)
        findViewById<View>(R.id.add).setOnClickListener { chooseHowToAdd() }
        findViewById<View>(R.id.lan_allow).setOnClickListener {
            bannerTapped = true
            askLocalNetwork.launch(LocalNetwork.PERMISSION)
        }
        if (savedInstanceState == null && !LocalNetwork.granted(this)) askLocalNetwork.launch(LocalNetwork.PERMISSION)
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
        searches.shutdownNow()
        super.onDestroy()
    }

    private fun checkAll() {
        if (!LocalNetwork.granted(this)) return
        for (tv in store.all()) {
            val link = tv.link
            // A probe gives up within 8 seconds, but one stuck in DNS can outlast a round.
            if (!inFlight.add(link.origin)) continue
            probes.execute {
                val status = TvProbe.status(link)
                main.post {
                    inFlight.remove(link.origin)
                    if (isDestroyed) return@post
                    val power = status.power
                    shown[link.origin] = when (status.reach) {
                        // Without stats (a wrong token, say) the power state is unknown.
                        TvProbe.Reach.ANSWERS -> when {
                            power == null -> Shown(Reach.ONLINE)
                            power.on -> Shown(Reach.ON)
                            else -> Shown(Reach.ASLEEP, power.label)
                        }
                        TvProbe.Reach.NO_ANSWER -> Shown(Reach.OFFLINE)
                        TvProbe.Reach.UNTRUSTED_CERT -> Shown(Reach.UNTRUSTED_CERT)
                    }
                    status.identity?.let { remember(tv.id, it) }
                    adapter?.statusChanged(link.origin)
                    if (status.reach == TvProbe.Reach.NO_ANSWER && tv.mac != null) refindMoved()
                }
            }
        }
    }

    /**
     * A saved TV that stops answering may only have a new address from the
     * router. An SSDP search, at most every two minutes while one is offline,
     * finds the webOS TVs that are on; one whose MAC matches a saved TV takes
     * the new address, keeping its name and token.
     */
    private fun refindMoved() {
        val now = SystemClock.elapsedRealtime()
        if (searching || now - lastRefind < REFIND_MS) return
        searching = true
        lastRefind = now
        val tokens = store.all().map { it.link.token }
        searches.execute {
            val found = Discovery.find(applicationContext, tokens, sweep = false)
            main.post {
                searching = false
                if (isDestroyed) return@post
                for (f in found) {
                    val mac = f.identity?.mac ?: continue
                    val moved = store.byMac(mac) ?: continue
                    if (moved.link.origin == f.link.origin) continue
                    store.save(moved.copy(link = TvLink(f.link.origin, moved.link.token)))
                    Toast.makeText(this, getString(R.string.tv_moved, moved.name, f.link.label), Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    /** Stores what a TV said about itself, against the entry it is now. */
    private fun remember(id: String, identity: TvProbe.Identity) {
        val current = store.get(id) ?: return
        if (current.mac == identity.mac && current.wakeOnLan == identity.wakeOnLan) return
        store.save(current.copy(mac = identity.mac, wakeOnLan = identity.wakeOnLan))
        refresh()
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
        lanBanner.visibility = if (LocalNetwork.granted(this)) View.GONE else View.VISIBLE
        // Covers a TV just added or edited; already-running probes are skipped.
        checkAll()
        empty.visibility = if (tvs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun chooseHowToAdd() {
        val choices = arrayOf(
            getString(R.string.tv_add_scan),
            getString(R.string.tv_add_find),
            getString(R.string.tv_add_manual),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tv_add)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> startScan()
                    1 -> findTvs()
                    else -> editTv(null, null, null)
                }
            }
            .show()
    }

    /** Searches the network and offers the Glasshouse TVs not in the list yet. */
    private fun findTvs() {
        if (!LocalNetwork.granted(this)) {
            bannerTapped = true
            askLocalNetwork.launch(LocalNetwork.PERMISSION)
            return
        }
        val bar = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val waiting = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.find_searching)
            .setView(bar)
            .setNegativeButton(R.string.cancel, null)
            .show()
        val tokens = store.all().map { it.link.token }
        searches.execute {
            val found = Discovery.find(applicationContext, tokens, sweep = true)
            main.post {
                if (isDestroyed || !waiting.isShowing) return@post
                waiting.dismiss()
                val saved = store.all()
                val fresh = found.filter { f ->
                    saved.none { it.link.origin == f.link.origin || (f.identity != null && it.mac == f.identity.mac) }
                }.sortedBy { it.link.label }
                showFound(fresh)
            }
        }
    }

    private fun showFound(found: List<Discovery.Found>) {
        if (found.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.find_none_title)
                .setMessage(R.string.find_none)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val rows = found.map { f ->
            f.identity?.name?.let { getString(R.string.tv_status_line, it, f.link.label) } ?: f.link.label
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.find_found)
            .setItems(rows) { _, which ->
                val f = found[which]
                editTv(null, f.link, f.identity)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startScan() {
        scan.launch(ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.scan_prompt))
            .setBeepEnabled(false))
    }

    /**
     * Edits [tv], or adds a new TV prefilled from [scanned] when [tv] is null.
     * [identity] is what a scanned TV already said about itself.
     */
    private fun editTv(tv: Tv?, scanned: TvLink?, identity: TvProbe.Identity?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_tv, null)
        val name = view.findViewById<TextInputEditText>(R.id.name)
        val address = view.findViewById<TextInputEditText>(R.id.address)
        val addressLayout = view.findViewById<TextInputLayout>(R.id.address_layout)
        val token = view.findViewById<TextInputEditText>(R.id.token)

        val start = tv?.link ?: scanned
        name.setText(tv?.name ?: identity?.name ?: start?.let { getString(R.string.tv_default_name, it.label) } ?: "")
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
                val saved = Tv(
                    id = tv?.id ?: TvStore.newId(),
                    name = label,
                    link = link,
                    mac = identity?.mac ?: tv?.mac.takeIf { tv?.link?.origin == link.origin },
                    wakeOnLan = identity?.wakeOnLan ?: tv?.wakeOnLan,
                )
                store.save(saved)
                refresh()
                dialog.dismiss()
                if (tv == null) mergeIfKnown(saved)
            }
        }
        dialog.show()
    }

    /**
     * A TV just added by address may be one already in the list under an old
     * address. Once it answers with its MAC, the older entry takes the new
     * address and keeps its name; the new entry goes.
     */
    private fun mergeIfKnown(added: Tv) {
        probes.execute {
            val identity = added.mac?.let { TvProbe.Identity(it, added.wakeOnLan) } ?: TvProbe.identify(added.link)
            main.post {
                if (isDestroyed || identity == null) return@post
                val older = store.all().firstOrNull { it.id != added.id && it.mac == identity.mac }
                if (older == null) {
                    remember(added.id, identity)
                    return@post
                }
                store.save(older.copy(link = added.link, wakeOnLan = identity.wakeOnLan ?: older.wakeOnLan))
                store.remove(added.id)
                refresh()
                Toast.makeText(this, getString(R.string.tv_updated, older.name), Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun open(tv: Tv, wake: Boolean = false) {
        startActivity(Intent(this, DashboardActivity::class.java)
            .putExtra(DashboardActivity.EXTRA_TV_ID, tv.id)
            .putExtra(DashboardActivity.EXTRA_WAKE, wake))
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
        private val power = view.findViewById<ImageButton>(R.id.power)
        private val more = view.findViewById<ImageButton>(R.id.more)

        fun bind(tv: Tv) {
            name.text = tv.name
            val state = shown[tv.link.origin] ?: Shown(Reach.CHECKING)
            val (label, dot) = when (state.reach) {
                Reach.ON -> getString(R.string.tv_on) to R.drawable.dot_online
                Reach.ASLEEP -> state.label.orEmpty().ifEmpty { getString(R.string.tv_standby) } to R.drawable.dot_asleep
                Reach.ONLINE -> getString(R.string.tv_online) to R.drawable.dot_online
                Reach.OFFLINE -> getString(R.string.tv_offline) to R.drawable.dot_offline
                Reach.UNTRUSTED_CERT -> getString(R.string.tv_untrusted) to R.drawable.dot_offline
                Reach.CHECKING -> getString(R.string.tv_checking) to R.drawable.dot_checking
            }
            address.text = getString(R.string.tv_status_line, label, tv.link.label)
            address.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
            itemView.setOnClickListener { open(tv) }

            // A dark TV that answers is turned on by its server; one that does
            // not, by Wake-on-LAN, which needs its MAC.
            val canTurnOn = state.reach == Reach.ASLEEP || (state.reach == Reach.OFFLINE && tv.mac != null)
            power.visibility = if (canTurnOn) View.VISIBLE else View.GONE
            power.contentDescription = getString(R.string.tv_turn_on_named, tv.name)
            power.setOnClickListener { open(tv, wake = true) }

            more.contentDescription = getString(R.string.tv_more, tv.name)
            more.setOnClickListener {
                val menu = PopupMenu(this@MainActivity, more)
                menu.inflate(R.menu.tv_item)
                menu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_edit -> editTv(tv, null, null)
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
        private const val REFIND_MS = 120_000L
    }
}
