package wallpaper

/** Returns all matches, preserving library order and without a hard result cap. */
internal fun matchingTargets(
    options: List<Pair<Long, String>>,
    searchText: String
): List<Pair<Long, String>> {
    val search = searchText.trim()
    return if (search.isEmpty()) options
    else options.filter { (_, label) -> label.contains(search, ignoreCase = true) }
}
