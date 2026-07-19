"""Pure Pad map tile styling and rendering helpers."""
from __future__ import annotations

from collections import deque

KINDS = [
    ("UNKNOWN", 0xFFE6E0D5),
    ("GRASS", 0xFFE6E0D5),
    ("INDOOR_FLOOR", 0xFFE9E1D3),
    ("BUILDING", 0xFFDAD5CF),
    ("WATER", 0xFF9DBDCE),
    ("TREE", 0xFFBCD3B2),
    ("FARMLAND", 0xFFE0D5B7),
    ("ROCK", 0xFFD0CDC6),
    ("SNOW", 0xFFE8E5DE),
]

STYLE_COLORS = {
    "base": 0xFFE6E0D5,
    "farmland": 0xFFE0D5B7,
    "green": 0xFFBCD3B2,
    "water": 0xFF9DBDCE,
    "indoor_floor": 0xFFE8DED0,
    "building_zone": 0xFFD1CBC3,
    "building_core": 0xFFBEB7AE,
    "water_line": 0xFF86AFC4,
}


def argb_to_rgb(color: int):
    return ((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF)


def idx(width: int, x: int, z: int) -> int:
    return z * width + x


def mask_for(tiles, width, kind_name):
    ordinal = next(i for i, (name, _) in enumerate(KINDS) if name == kind_name)
    return [tile == ordinal for tile in tiles]


def count_radius(mask, width, height, x, z, radius):
    return sum(
        1 for dz in range(-radius, radius + 1)
        for dx in range(-radius, radius + 1)
        if 0 <= x + dx < width and 0 <= z + dz < height
        and mask[idx(width, x + dx, z + dz)]
    )


def large_components(source, width, height, min_area):
    output = [False] * len(source)
    visited = [False] * len(source)
    for start, value in enumerate(source):
        if not value or visited[start]:
            continue
        queue = deque([start])
        visited[start] = True
        component = []
        while queue:
            current = queue.popleft()
            component.append(current)
            x, z = current % width, current // width
            for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, nz = x + dx, z + dz
                if 0 <= nx < width and 0 <= nz < height:
                    neighbor = idx(width, nx, nz)
                    if source[neighbor] and not visited[neighbor]:
                        visited[neighbor] = True
                        queue.append(neighbor)
        if len(component) >= min_area:
            for item in component:
                output[item] = True
    return output


def soften_area(source, width, height, radius, threshold):
    output = source[:]
    for z in range(height):
        for x in range(width):
            if not source[idx(width, x, z)] and count_radius(source, width, height, x, z, radius) >= threshold:
                output[idx(width, x, z)] = True
    return output


def outline_with_source(source, width, height):
    output = source[:]
    for z in range(height):
        for x in range(width):
            if not source[idx(width, x, z)] and count_radius(source, width, height, x, z, 1) > 0:
                output[idx(width, x, z)] = True
    return output


def fill_small_interior_gaps(mask, width, height):
    output = mask[:]
    for z in range(1, height - 1):
        for x in range(1, width - 1):
            i = idx(width, x, z)
            if not output[i]:
                cardinal = sum(output[j] for j in (idx(width, x - 1, z), idx(width, x + 1, z),
                                                    idx(width, x, z - 1), idx(width, x, z + 1)))
                if cardinal >= 3 or (cardinal >= 2 and count_radius(output, width, height, x, z, 1) >= 5):
                    output[i] = True
    return output


def fill_single_cell_holes(mask, width, height):
    output = mask[:]
    for z in range(1, height - 1):
        for x in range(1, width - 1):
            i = idx(width, x, z)
            if not output[i] and all(output[j] for j in (idx(width, x - 1, z), idx(width, x + 1, z),
                                                          idx(width, x, z - 1), idx(width, x, z + 1))):
                output[i] = True
    return output


def fill_enclosed_holes(mask, width, height, max_area):
    output = mask[:]
    visited = [False] * len(mask)

    def flood_open(start_x, start_z):
        start = idx(width, start_x, start_z)
        if output[start] or visited[start]:
            return
        queue = deque([start])
        visited[start] = True
        while queue:
            current = queue.popleft()
            x, z = current % width, current // width
            for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, nz = x + dx, z + dz
                if 0 <= nx < width and 0 <= nz < height:
                    neighbor = idx(width, nx, nz)
                    if not output[neighbor] and not visited[neighbor]:
                        visited[neighbor] = True
                        queue.append(neighbor)

    for z in range(height):
        flood_open(0, z)
        flood_open(width - 1, z)
    for x in range(width):
        flood_open(x, 0)
        flood_open(x, height - 1)

    for start, value in enumerate(output):
        if value or visited[start]:
            continue
        queue = deque([start])
        component = []
        visited[start] = True
        while queue:
            current = queue.popleft()
            component.append(current)
            x, z = current % width, current // width
            for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, nz = x + dx, z + dz
                if 0 <= nx < width and 0 <= nz < height:
                    neighbor = idx(width, nx, nz)
                    if not output[neighbor] and not visited[neighbor]:
                        visited[neighbor] = True
                        queue.append(neighbor)
        if len(component) <= max_area and has_building_ring(output, width, height, component):
            for item in component:
                output[item] = True
    return output


def has_building_ring(mask, width, height, component):
    required = max(4, min(48, len(component) // 2))
    adjacent = 0
    for i in component:
        x, z = i % width, i // width
        if any(0 <= nx < width and 0 <= nz < height and mask[idx(width, nx, nz)]
               for nx, nz in ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1))):
            adjacent += 1
            if adjacent >= required:
                return True
    return False


def building_core(source, width, height, threshold):
    return [source[idx(width, x, z)] and count_radius(source, width, height, x, z, 1) >= threshold
            for z in range(height) for x in range(width)]


def subtract(source, remove):
    return [a and not b for a, b in zip(source, remove)]


def style_snapshot(snapshot):
    width, height, tiles = snapshot["width"], snapshot["height"], snapshot["tiles"]
    tree = mask_for(tiles, width, "TREE")
    farmland = mask_for(tiles, width, "FARMLAND")
    indoor_floor = mask_for(tiles, width, "INDOOR_FLOOR")
    building = mask_for(tiles, width, "BUILDING")
    water = mask_for(tiles, width, "WATER")
    indoor_map = sum(indoor_floor) >= 8
    tree_component = large_components(tree, width, height, 10)
    farmland_component = large_components(farmland, width, height, 8)
    water_component = large_components(water, width, height, 18)
    water_area = soften_area(water_component, width, height, 1, 5)
    building_footprint = large_components(building, width, height, 2 if indoor_map else 4)
    if indoor_map:
        building_footprint = fill_small_interior_gaps(building_footprint, width, height)
        building_zone = outline_with_source(building_footprint, width, height)
        core = building_core(building_footprint, width, height, 4)
    else:
        building_footprint = fill_single_cell_holes(building_footprint, width, height)
        building_footprint = fill_enclosed_holes(building_footprint, width, height, max(24, width * height // 18))
        building_zone = building_footprint[:]
        core = building_core(building_footprint, width, height, 6)
    return {
        "farmland_area": soften_area(farmland_component, width, height, 1, 4),
        "green_area": soften_area(tree_component, width, height, 2, 9),
        "water_area": water_area,
        "water_line": subtract(water, water_area),
        "indoor_floor": indoor_floor,
        "building_footprint": building_footprint,
        "building_zone": building_zone,
        "building_core": core,
    }


def render_raw(snapshot):
    return [argb_to_rgb(KINDS[tile][1] if 0 <= tile < len(KINDS) else KINDS[0][1])
            for tile in snapshot["tiles"]]


def render_styled(snapshot):
    pixels = [argb_to_rgb(STYLE_COLORS["base"])] * (snapshot["width"] * snapshot["height"])
    styled = style_snapshot(snapshot)
    for mask, color in ((styled["farmland_area"], "farmland"), (styled["green_area"], "green"),
                        (styled["water_area"], "water"), (styled["indoor_floor"], "indoor_floor"),
                        (styled["building_zone"], "building_zone"), (styled["building_core"], "building_core"),
                        (styled["water_line"], "water_line")):
        rgb = argb_to_rgb(STYLE_COLORS[color])
        for i, enabled in enumerate(mask):
            if enabled:
                pixels[i] = rgb
    return pixels


def render_mask(mask):
    return [(190, 183, 174) if enabled else (230, 224, 213) for enabled in mask]