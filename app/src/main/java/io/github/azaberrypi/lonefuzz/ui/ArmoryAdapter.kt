package io.github.azaberrypi.lonefuzz.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.github.azaberrypi.lonefuzz.HitStore
import io.github.azaberrypi.lonefuzz.R
import io.github.azaberrypi.lonefuzz.RecipeType
import io.github.azaberrypi.lonefuzz.model.ComponentKind

class ArmoryAdapter(
    private val onShot: (HitStore.Entry) -> Unit,
    private val onBurst: (HitStore.Entry) -> Unit,
    private val onDelete: (HitStore.Entry) -> Unit,
) : RecyclerView.Adapter<ArmoryAdapter.VH>() {

    private val entries = mutableListOf<HitStore.Entry>()

    fun submit(items: List<HitStore.Entry>) {
        entries.clear()
        entries.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_armory, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(entries[position])

    override fun getItemCount(): Int = entries.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val app: TextView = view.findViewById(R.id.armoryApp)
        private val kind: TextView = view.findViewById(R.id.armoryKind)
        private val component: TextView = view.findViewById(R.id.armoryComponent)
        private val pkg: TextView = view.findViewById(R.id.armoryPackage)
        private val recipe: TextView = view.findViewById(R.id.armoryRecipe)
        private val version: TextView = view.findViewById(R.id.armoryVersion)
        private val shot: MaterialButton = view.findViewById(R.id.armoryShot)
        private val burst: MaterialButton = view.findViewById(R.id.armoryBurst)
        private val delete: MaterialButton = view.findViewById(R.id.armoryDelete)

        fun bind(entry: HitStore.Entry) {
            val ctx = itemView.context
            app.text = entry.appLabel.ifEmpty { entry.packageName }
            kind.text = when (entry.kind) {
                ComponentKind.ACTIVITY -> "A"
                ComponentKind.SERVICE -> "S"
                ComponentKind.RECEIVER -> "R"
                ComponentKind.PROVIDER -> "P"
            }
            component.text = entry.className.substringAfterLast('.')
            pkg.text = "${entry.packageName} / ${entry.className}"
            val type = runCatching { RecipeType.valueOf(entry.recipeName) }.getOrNull()
            recipe.text = type?.let { ctx.getString(it.labelRes) } ?: entry.recipeName
            version.text = entry.appVersion
            shot.setOnClickListener { onShot(entry) }
            burst.isEnabled = entry.kind != ComponentKind.ACTIVITY
            burst.setOnClickListener { onBurst(entry) }
            delete.setOnClickListener { onDelete(entry) }
        }
    }
}
