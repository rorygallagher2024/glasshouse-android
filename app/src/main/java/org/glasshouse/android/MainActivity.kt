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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
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
    private lateinit var count: TextView
    private var adapter: TvAdapter? = null

    private lateinit var listRefresh: SwipeRefreshLayout
    private lateinit var lastSeen: LastSeen

    /** A power command under way on a TV, by origin; its card says so meanwhile. */
    private enum class Busy { TURNING_ON, TURNING_OFF }
    private val busy = mutableMapOf<String, Busy>()

    /** Power and volume run apart from the probes: waking a TV can take a minute. */
    private val commands = Executors.newFixedThreadPool(2)

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
                        model = identity?.model ?: known.model,
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
        // The toolbar holds its own mark and name.
        supportActionBar?.setDisplayShowTitleEnabled(false)
        padForSystemBars(findViewById(R.id.appbar), findViewById(R.id.content))

        store = TvStore(this)
        lastSeen = LastSeen(this)
        // Opens on each TV's last state seen, dimmed until it answers again.
        shown.putAll(lastSeen.all())
        list = findViewById(R.id.list)
        listRefresh = findViewById(R.id.list_refresh)
        listRefresh.setOnRefreshListener { checkAll(); stopRefreshWhenDone() }
        empty = findViewById(R.id.empty)
        lanBanner = findViewById(R.id.lan_banner)
        count = findViewById(R.id.count)
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
        commands.shutdownNow()
        super.onDestroy()
    }

    private fun checkAll() {
        if (!LocalNetwork.granted(this)) return
        for (tv in store.all()) check(tv)
    }

    private fun check(tv: Tv) {
        val link = tv.link
        // A probe gives up within 8 seconds, but one stuck in DNS can outlast a round.
        if (!inFlight.add(link.origin)) return
        probes.execute {
            val status = TvProbe.status(link)
            main.post {
                inFlight.remove(link.origin)
                if (isDestroyed) return@post
                val now = status.toShown()
                shown[link.origin] = now
                lastSeen.save(link.origin, now)
                status.identity?.let { remember(tv.id, it) }
                adapter?.statusChanged(link.origin)
                if (status.reach == TvProbe.Reach.NO_ANSWER && tv.mac != null) refindMoved()
            }
        }
    }

    /** Ends the pull-to-refresh spinner once every probe now running has answered. */
    private fun stopRefreshWhenDone() {
        if (inFlight.isEmpty() || isDestroyed) {
            listRefresh.isRefreshing = false
            return
        }
        main.postDelayed({ stopRefreshWhenDone() }, 250)
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
        val model = identity.model ?: current.model
        if (current.mac == identity.mac && current.wakeOnLan == identity.wakeOnLan && current.model == model) return
        store.save(current.copy(mac = identity.mac, wakeOnLan = identity.wakeOnLan, model = model))
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
        lastSeen.keepOnly(tvs.map { it.link.origin }.toSet())
        lanBanner.visibility = if (LocalNetwork.granted(this)) View.GONE else View.VISIBLE
        // Covers a TV just added or edited; already-running probes are skipped.
        checkAll()
        empty.visibility = if (tvs.isEmpty()) View.VISIBLE else View.GONE
        count.visibility = if (tvs.isEmpty()) View.GONE else View.VISIBLE
        count.text = getString(R.string.tv_count, tvs.size)
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
                    // Kept through an address change: it is what finds the TV again,
                    // and a different TV at the new address replaces it when it answers.
                    mac = identity?.mac ?: tv?.mac,
                    wakeOnLan = identity?.wakeOnLan ?: tv?.wakeOnLan,
                    model = identity?.model ?: tv?.model,
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
                store.save(older.copy(
                    link = added.link,
                    wakeOnLan = identity.wakeOnLan ?: older.wakeOnLan,
                    model = identity.model ?: older.model,
                ))
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

    /**
     * Turns a TV on or off from its card. Turning on goes through the TV's
     * server when it answers, else Wake-on-LAN; the card says "Turning on"
     * meanwhile, and the TV is asked again once it is done.
     */
    private fun setPower(tv: Tv, on: Boolean) {
        val origin = tv.link.origin
        if (busy.containsKey(origin)) return
        busy[origin] = if (on) Busy.TURNING_ON else Busy.TURNING_OFF
        adapter?.statusChanged(origin)
        commands.execute {
            val failure: String? = if (on) {
                when (val r = TvPower.turnOn(this, tv) { !isDestroyed }) {
                    TvPower.TurnOn.On -> null
                    is TvPower.TurnOn.Refused -> getString(R.string.tv_turn_on_failed, tv.name,
                        r.reason.ifEmpty { getString(R.string.dash_power_on_no_reply) })
                    TvPower.TurnOn.NoAnswer -> getString(
                        if (tv.wakeOnLan == false) R.string.tv_wake_failed_setting else R.string.tv_wake_failed, tv.name)
                }
            } else {
                TvPower.turnOff(tv)?.let { reason ->
                    getString(R.string.tv_turn_off_failed, tv.name, reason.ifEmpty { getString(R.string.dash_power_on_no_reply) })
                }
            }
            main.post {
                busy.remove(origin)
                if (isDestroyed) return@post
                if (failure != null) Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
                adapter?.statusChanged(origin)
                // A TV going to standby takes a moment to say so.
                main.postDelayed({ if (!isDestroyed) store.get(tv.id)?.let { check(it) } }, if (on) 500 else 3000)
            }
        }
    }

    /** A volume step or mute, shown at once and corrected by the TV's next answer. */
    private fun volume(tv: Tv, change: (TvProbe.Showing) -> TvProbe.Showing, send: () -> String?) {
        val origin = tv.link.origin
        val before = shown[origin] ?: return
        val showing = before.showing ?: return
        shown[origin] = before.copy(showing = change(showing))
        adapter?.statusChanged(origin)
        commands.execute {
            val problem = send()
            main.post {
                if (isDestroyed) return@post
                if (problem != null) {
                    Toast.makeText(this, getString(R.string.tv_volume_failed, tv.name,
                        problem.ifEmpty { getString(R.string.dash_power_on_no_reply) }), Toast.LENGTH_LONG).show()
                }
                store.get(tv.id)?.let { check(it) }
            }
        }
    }

    private inner class TvHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card = view.findViewById<View>(R.id.card)
        private val model = view.findViewById<TextView>(R.id.model)
        private val pill = view.findViewById<TextView>(R.id.pill)
        private val name = view.findViewById<TextView>(R.id.name)
        private val showingRow = view.findViewById<View>(R.id.showing)
        private val source = view.findViewById<TextView>(R.id.source)
        private val range = view.findViewById<TextView>(R.id.range)
        private val picture = view.findViewById<TextView>(R.id.picture)
        private val volumeRow = view.findViewById<View>(R.id.volume_row)
        private val volumeLevel = view.findViewById<TextView>(R.id.volume_level)
        private val volumeDown = view.findViewById<ImageButton>(R.id.volume_down)
        private val volumeUp = view.findViewById<ImageButton>(R.id.volume_up)
        private val mute = view.findViewById<ImageButton>(R.id.mute)
        private val wakeNote = view.findViewById<TextView>(R.id.wake_note)
        private val power = view.findViewById<MaterialButton>(R.id.power)
        private val openButton = view.findViewById<MaterialButton>(R.id.open)
        private val address = view.findViewById<TextView>(R.id.address)
        private val more = view.findViewById<ImageButton>(R.id.more)

        fun bind(tv: Tv) {
            val state = shown[tv.link.origin] ?: Shown(Reach.CHECKING)
            val busyNow = busy[tv.link.origin]
            model.text = tv.model.orEmpty()
            name.text = tv.name
            address.text = tv.link.label

            // The pill: the dashboard's green for on, a ring for a TV that
            // answers while dark, grey for one that does not.
            val (label, dot, frame, colour) = when (state.reach) {
                Reach.ON -> Pill(getString(R.string.tv_on), R.drawable.dot_online, R.drawable.pill_on, R.color.green)
                Reach.ONLINE -> Pill(getString(R.string.tv_online), R.drawable.dot_online, R.drawable.pill_on, R.color.green)
                Reach.ASLEEP -> Pill(
                    state.label.orEmpty().ifEmpty { getString(R.string.tv_standby) },
                    R.drawable.dot_asleep, R.drawable.pill_asleep, R.color.fg80,
                )
                Reach.OFFLINE -> Pill(getString(R.string.tv_offline), R.drawable.dot_offline, R.drawable.pill_off, R.color.fg50)
                Reach.UNTRUSTED_CERT -> Pill(getString(R.string.tv_untrusted), R.drawable.dot_offline, R.drawable.pill_off, R.color.fg60)
                Reach.CHECKING -> Pill(getString(R.string.tv_checking), R.drawable.dot_checking, R.drawable.pill_off, R.color.fg50)
            }
            pill.text = label
            pill.setBackgroundResource(frame)
            pill.setTextColor(getColor(colour))
            pill.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
            // Recalled from last time: shown, but faint until the TV answers.
            pill.alpha = if (state.stale) 0.45f else 1f
            name.setTextColor(getColor(if (state.reach == Reach.OFFLINE) R.color.fg50 else R.color.fg))

            val showing = state.showing.takeIf { state.reach == Reach.ON }
            showingRow.visibility = if (showing != null) View.VISIBLE else View.GONE
            if (showing != null) {
                bindPart(source, showing.source)
                bindPart(range, showing.range)
                bindPart(picture, showing.picture)
            }

            val volume = showing?.volume
            volumeRow.visibility = if (volume != null && busyNow == null) View.VISIBLE else View.GONE
            if (volume != null) {
                volumeLevel.text = if (showing.muted) getString(R.string.tv_volume_muted) else getString(R.string.tv_volume_level, volume)
                mute.setImageResource(if (showing.muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
                mute.contentDescription = getString(if (showing.muted) R.string.tv_unmute else R.string.tv_mute)
                volumeDown.setOnClickListener {
                    volume(tv, { it.copy(volume = maxOf(0, (it.volume ?: 0) - 1)) }) { TvProbe.volumeStep(tv.link, up = false) }
                }
                volumeUp.setOnClickListener {
                    volume(tv, { it.copy(volume = minOf(100, (it.volume ?: 0) + 1)) }) { TvProbe.volumeStep(tv.link, up = true) }
                }
                mute.setOnClickListener {
                    val muteNow = !showing.muted
                    volume(tv, { it.copy(muted = muteNow) }) { TvProbe.mute(tv.link, muteNow) }
                }
            }

            // Wake-on-LAN's setting is shown whatever the state: it can only be
            // switched on while the TV is on.
            val wakeOff = tv.wakeOnLan == false
            wakeNote.visibility = if (wakeOff) View.VISIBLE else View.GONE

            // On turns off; a dark TV that answers is turned on by its server;
            // one that does not, by Wake-on-LAN, which needs its MAC and the
            // TV's setting on. A TV whose power state is not known gets neither.
            val canWake = tv.mac != null && !wakeOff
            when {
                busyNow != null -> {
                    power.visibility = View.VISIBLE
                    power.isEnabled = false
                    power.text = getString(if (busyNow == Busy.TURNING_ON) R.string.tv_turning_on else R.string.tv_turning_off)
                }
                state.reach == Reach.ON -> showPower(R.string.tv_turn_off, enabled = true) { setPower(tv, on = false) }
                state.reach == Reach.ASLEEP -> showPower(R.string.tv_turn_on, enabled = true) { setPower(tv, on = true) }
                state.reach == Reach.OFFLINE -> showPower(R.string.tv_turn_on, enabled = canWake) { setPower(tv, on = true) }
                else -> power.visibility = View.GONE
            }
            power.contentDescription = getString(R.string.tv_power_named, power.text, tv.name)

            // An offline TV's dashboard has nothing to show.
            val reachable = state.reach != Reach.OFFLINE && busyNow == null
            openButton.isEnabled = reachable
            openButton.contentDescription = getString(R.string.tv_open_named, tv.name)
            openButton.setOnClickListener { open(tv) }
            card.setOnClickListener { open(tv) }

            more.contentDescription = getString(R.string.tv_more, tv.name)
            more.setOnClickListener {
                val menu = PopupMenu(this@MainActivity, more)
                menu.inflate(R.menu.tv_item)
                menu.menu.findItem(R.id.action_tile).isChecked = TileChoice.tv(this@MainActivity)?.id == tv.id
                menu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_edit -> editTv(tv, null, null)
                        R.id.action_tile -> {
                            TileChoice.choose(this@MainActivity, tv)
                            TvTileService.refresh(this@MainActivity)
                            Toast.makeText(this@MainActivity, getString(R.string.tv_tile_chosen, tv.name), Toast.LENGTH_SHORT).show()
                        }
                        R.id.action_remove -> confirmRemove(tv)
                    }
                    true
                }
                menu.show()
            }
        }

        private fun showPower(text: Int, enabled: Boolean, onClick: () -> Unit) {
            power.visibility = View.VISIBLE
            power.isEnabled = enabled
            power.text = getString(text)
            power.setOnClickListener { onClick() }
        }

        private fun bindPart(view: TextView, text: String?) {
            view.text = text.orEmpty()
            view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private data class Pill(val label: String, val dot: Int, val frame: Int, val colour: Int)

    companion object {
        private const val RECHECK_MS = 15_000L
        private const val REFIND_MS = 120_000L
    }
}
