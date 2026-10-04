package org.glasshouse.android

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** The saved TVs: open one, add one by QR code or address, edit or remove. */
class MainActivity : AppCompatActivity() {

    private lateinit var store: TvStore
    private lateinit var list: RecyclerView
    private lateinit var empty: View

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
        refresh()
    }

    private fun refresh() {
        val tvs = store.all()
        list.adapter = TvAdapter(tvs)
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
    }

    private inner class TvHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.name)
        private val address = view.findViewById<TextView>(R.id.address)
        private val more = view.findViewById<ImageButton>(R.id.more)

        fun bind(tv: Tv) {
            name.text = tv.name
            address.text = tv.link.label
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
}
