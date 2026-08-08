package me.usainsrht.basicdeathchest.database.model;

/**
 * Outcome of death-chest placement for a recorded death.
 */
public enum ChestStatus {
    /** Chest was placed successfully. */
    PLACED,
    /** No items to store — chest was not created. */
    NO_ITEMS,
    /** No suitable block found for placement. */
    BLOCK_OBSTRUCTION,
    /** Missing plugin permission or region build denial. */
    NO_PERMISSION,
    /** World not allowed for chest placement (entry still logged). */
    WORLD_FILTERED,
    /** Legacy entries saved before status tracking existed. */
    UNKNOWN;

    public static ChestStatus fromStorage(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return ChestStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
