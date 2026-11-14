package inego.zungjunggame.render.table

import inego.zungjunggame.render.seats.PlayerAreaLayout
import inego.zungjunggame.render.tiles.SlotKey
import inego.zungjunggame.render.tiles.SlotKind

internal fun slotKeyFor(seat: Int, p: PlayerAreaLayout.TilePlacement): SlotKey {
    val kind = when (p.region) {
        PlayerAreaLayout.Region.Hand -> SlotKind.Hand
        PlayerAreaLayout.Region.Drawn -> SlotKind.Drawn
        PlayerAreaLayout.Region.Meld -> SlotKind.Meld
        PlayerAreaLayout.Region.River -> SlotKind.River
    }
    return SlotKey(kind, seat, p.regionIndex, p.regionSubIndex)
}
