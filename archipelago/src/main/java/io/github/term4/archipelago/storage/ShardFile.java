package io.github.term4.archipelago.storage;

import io.github.term4.archipelago.shard.Shard;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.world.DimensionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The lib format ({@code .shard}): a shard IS its diff. One gzip'd NBT compound - a string-state palette (version
 * stable), columnar position/state arrays, dimension bounds, sparse per-entry block-NBT/handler detail, sky
 * overrides, an optional {@code before} column (what each position showed beneath the diff - unread today,
 * carried for commit-history/rollback), and a {@link DataKey} section for EVERYTHING else (entities =
 * {@code EntityDescriptors.write/read}, light, items, any game data). A whole layer tree fits one file
 * ({@link #saveTree}). No polar dependency; for big diffs polar's palette sections still compress tighter.
 */
public final class ShardFile {

    private static final int FORMAT = 1;
    private static final int NO_BEFORE = -1;

    /** This layer's name hint, in the user-data section ({@link #saveTree}'s {@code names} fills it). */
    public static final DataKey<String> NAME = new DataKey<>("archipelago:name", Codec.STRING);
    /** The parent layer's name hint - a child file is meaningless without its chain; {@link #join} resolves it. */
    public static final DataKey<String> PARENT = new DataKey<>("archipelago:parent", Codec.STRING);

    private ShardFile() {}

    public static byte @NotNull [] save(@NotNull Shard shard) {
        return save(shard, null, false);
    }

    public static byte @NotNull [] save(@NotNull Shard shard, @Nullable Consumer<UserData.Writer> userData) {
        return save(shard, userData, false);
    }

    /** {@code withBefore} also records what each position showed beneath the diff (unloaded-chunk = unknown). */
    public static byte @NotNull [] save(@NotNull Shard shard, @Nullable Consumer<UserData.Writer> userData,
                                        boolean withBefore) {
        // one relay for the whole capture: diff and the before column read the same instant, not one tick apart
        Content content = shard.relay(() -> {
            Content snap = snapshot(shard);
            if (!withBefore) return snap;
            Map<Point, Block> before = new HashMap<>();
            Shard parent = shard.parent();
            for (Point pos : snap.diff().keySet()) {
                if (!shard.instance().isChunkLoaded(pos)) continue; // unknown
                before.put(pos, parent != null ? parent.getBlock(pos) : shard.instance().getBlock(pos));
            }
            return new Content(snap.minY(), snap.maxY(), snap.diff(), before,
                    snap.time(), snap.weather(), snap.user(), null);
        });
        return content.encode(userData);
    }

    public static void save(@NotNull Shard shard, @NotNull Path path) throws IOException {
        save(shard, path, null, false);
    }

    public static void save(@NotNull Shard shard, @NotNull Path path, @Nullable Consumer<UserData.Writer> userData) throws IOException {
        save(shard, path, userData, false);
    }

    /** Atomic file save: written beside {@code path}, then moved into place - a crash never leaves a torn file. */
    public static void save(@NotNull Shard shard, @NotNull Path path, @Nullable Consumer<UserData.Writer> userData,
                            boolean withBefore) throws IOException {
        Durable.write(path, save(shard, userData, withBefore));
    }

    /** @deprecated call {@link Durable#write} directly. */
    @Deprecated
    public static void moveIntoPlace(Path path, byte[] bytes) throws IOException {
        Durable.write(path, bytes);
    }

    public static @NotNull UserData.View load(@NotNull Shard shard, @NotNull Path path) throws IOException {
        return load(shard, Files.readAllBytes(path));
    }

    /** {@link #load(Shard, byte[])} for an already-parsed {@link Content} - the in-memory half (a live recording's t0 loads without re-encoding). */
    public static @NotNull UserData.View load(@NotNull Shard shard, @NotNull Content content) {
        if (content.tree() != null) throw new IllegalArgumentException("a tree file - load with loadTree");
        apply(shard, content);
        return new UserData.View(content.user());
    }

    /** Just the file's user-data section, no shard touched - listing saved games/replays by their metadata. */
    public static @NotNull UserData.View userData(byte @NotNull [] data) {
        return new UserData.View(decode(data).user());
    }

    /** {@link #userData(byte[])} over an already-{@link #decode decoded} file (validate-then-load without a second decode). */
    public static @NotNull UserData.View userData(@NotNull Content content) {
        return new UserData.View(content.user());
    }

    /**
     * Captures {@code shard}'s persistable state now (diff + sky + bounds) for a later {@link Content#encode
     * encode} - a recorder snapshots its t0 and serializes once at stop, without holding the file bytes.
     */
    public static @NotNull Content snapshot(@NotNull Shard shard) {
        return new Content(shard.dimension().minY(), shard.dimension().maxY(), shard.diff(), null,
                shard.timeOverride(), shard.weatherOverride(), CompoundBinaryTag.empty(), null);
    }

    /**
     * One file's parsed state, field for field - {@link #decode} and {@link #encode} are the byte-level halves
     * (the polar transcoder rides them; no live shard needed). {@code before} null = no column saved; {@code
     * time} -1 / {@code weather} null = no override; {@code tree} = the verbatim child-layer section
     * ({@link #saveTree}), null when single-layer.
     */
    public record Content(int minY, int maxY, @NotNull Map<Point, Block> diff,
                          @Nullable Map<Point, Block> before, long time, @Nullable Weather weather,
                          @NotNull CompoundBinaryTag user, @Nullable ListBinaryTag tree) {

        public byte @NotNull [] encode() {
            return encode(null);
        }

        /** Serializes; {@code extra} entries merge over {@link #user} (new keys win). Deterministic byte-for-byte. */
        public byte @NotNull [] encode(@Nullable Consumer<UserData.Writer> extra) {
            CompoundBinaryTag u = user;
            if (extra != null) {
                UserData.Writer writer = new UserData.Writer();
                extra.accept(writer);
                u = CompoundBinaryTag.builder().put(u).put(writer.build()).build();
            }
            CompoundBinaryTag.Builder root = CompoundBinaryTag.builder()
                    .putInt("v", FORMAT)
                    .putInt("minY", minY).putInt("maxY", maxY);
            putLayer(root, diff, before, time, weather, u);
            if (tree != null) root.put("tree", tree);
            return Nbt.writeBytes(root.build(), BinaryTagIO.Compression.GZIP);
        }
    }

    /** The exact inverse of {@link Content#encode}; throws on a corrupt or foreign file, touching nothing. */
    public static @NotNull Content decode(byte @NotNull [] data) {
        CompoundBinaryTag root = read(data);
        Content layer = layer(root, root.getInt("minY"), root.getInt("maxY"));
        return root.get("tree") instanceof ListBinaryTag tree
                ? new Content(layer.minY(), layer.maxY(), layer.diff(), layer.before(), layer.time(),
                layer.weather(), layer.user(), tree)
                : layer;
    }

    // one layer's core sections: own palette + sorted columns + nbt detail + sky + user (self-contained,
    // so tree entries transcode/split without touching each other)
    private static void putLayer(CompoundBinaryTag.Builder out, Map<Point, Block> diff,
                                 @Nullable Map<Point, Block> before, long time, @Nullable Weather weather,
                                 CompoundBinaryTag user) {
        List<Map.Entry<Point, Block>> entries = new ArrayList<>(diff.entrySet());
        entries.sort(Map.Entry.comparingByKey(Nbt.POSITION_ORDER));
        Nbt.Palette palette = new Nbt.Palette();
        int[] xs = new int[entries.size()], ys = new int[entries.size()], zs = new int[entries.size()];
        int[] states = new int[entries.size()];
        int[] beforeCol = before != null ? new int[entries.size()] : null;
        int i = 0;
        for (Map.Entry<Point, Block> entry : entries) {
            Point pos = entry.getKey();
            xs[i] = pos.blockX();
            ys[i] = pos.blockY();
            zs[i] = pos.blockZ();
            states[i] = palette.id(Nbt.blockString(entry.getValue()));
            if (beforeCol != null) {
                Block covered = before.get(pos);
                beforeCol[i] = covered == null ? NO_BEFORE : palette.id(Nbt.blockString(covered));
            }
            i++;
        }
        ListBinaryTag.Builder<StringBinaryTag> states0 = ListBinaryTag.builder(BinaryTagTypes.STRING);
        for (String state : palette.entries()) states0.add(StringBinaryTag.stringBinaryTag(state));
        out.put("palette", states0.build())
                .putIntArray("x", xs).putIntArray("y", ys).putIntArray("z", zs);
        putColumn(out, "state", states, palette.size());
        if (beforeCol != null) putColumn(out, "before", beforeCol, palette.size());
        putNbt(out, entries);
        putSky(out, time, weather);
        if (!user.keySet().isEmpty()) out.put("user", user);
    }

    // one layer's core sections back into a Content (no tree, bounds handed down)
    private static Content layer(CompoundBinaryTag tag, int minY, int maxY) {
        Block[] palette = parsePalette(tag);
        int[] xs = tag.getIntArray("x"), ys = tag.getIntArray("y"), zs = tag.getIntArray("z");
        int[] states = column(tag, "state");
        // a corrupt file must fail as "corrupt", not as an index error deep in the load
        if (xs.length != states.length || ys.length != states.length || zs.length != states.length) {
            throw new IllegalArgumentException("corrupt shard file: column lengths disagree");
        }
        Map<Point, Block> diff = new HashMap<>();
        for (int i = 0; i < states.length; i++) diff.put(new Vec(xs[i], ys[i], zs[i]), palette[checkedIndex(states[i], palette.length)]);
        applyNbt(tag, xs, ys, zs, diff);
        Map<Point, Block> before = null;
        if (tag.get("before") != null) {
            before = new HashMap<>();
            int[] beforeCol = column(tag, "before");
            if (beforeCol.length != states.length) throw new IllegalArgumentException("corrupt shard file: before column length");
            for (int i = 0; i < beforeCol.length; i++) {
                if (beforeCol[i] != NO_BEFORE) before.put(new Vec(xs[i], ys[i], zs[i]), palette[checkedIndex(beforeCol[i], palette.length)]);
            }
        }
        CompoundBinaryTag sky = tag.getCompound("sky");
        return new Content(minY, maxY, diff, before, Nbt.skyTime(sky), Nbt.skyWeather(sky),
                tag.getCompound("user"), null);
    }

    public static byte @NotNull [] saveTree(@NotNull Shard root) {
        return saveTree(root, null, null);
    }

    /**
     * The whole tree in ONE file: root = the core sections, children = relative-diff entries, parents before
     * children (siblings stay relative - storage never merges them). {@code names} fills each layer's
     * {@link #NAME} hint (feed it {@code shards::nameOf}); {@code userData} runs per layer. Entities are a
     * recipe here too - descriptors under a per-layer {@link DataKey}.
     */
    public static byte @NotNull [] saveTree(@NotNull Shard root, @Nullable Function<Shard, String> names,
                                            @Nullable BiConsumer<Shard, UserData.Writer> userData) {
        List<Shard> shards = new ArrayList<>();
        collectTree(root, shards);
        Map<Shard, Integer> index = new HashMap<>();
        for (int i = 0; i < shards.size(); i++) index.put(shards.get(i), i);
        int minY = root.dimension().minY(), maxY = root.dimension().maxY();
        List<Content> layers = new ArrayList<>();
        int[] parents = new int[shards.size()];
        for (int i = 0; i < shards.size(); i++) {
            Shard s = shards.get(i);
            parents[i] = i == 0 ? -1 : index.get(s.parent());
            CompoundBinaryTag.Builder user = CompoundBinaryTag.builder();
            if (userData != null) {
                UserData.Writer writer = new UserData.Writer();
                userData.accept(s, writer);
                user.put(writer.build());
            }
            String name = names != null ? names.apply(s) : null;
            if (name != null) user.putString(NAME.id(), name);
            layers.add(new Content(minY, maxY, s.diff(), null, s.timeOverride(), s.weatherOverride(),
                    user.build(), null));
        }
        return encodeTree(minY, maxY, layers, parents);
    }

    private static void collectTree(Shard shard, List<Shard> out) {
        out.add(shard);
        for (Shard child : shard.children()) collectTree(child, out);
    }

    private static byte[] encodeTree(int minY, int maxY, List<Content> layers, int[] parents) {
        CompoundBinaryTag.Builder out = CompoundBinaryTag.builder()
                .putInt("v", FORMAT)
                .putInt("minY", minY).putInt("maxY", maxY);
        Content root = layers.get(0);
        putLayer(out, root.diff(), root.before(), root.time(), root.weather(), root.user());
        if (layers.size() > 1) {
            ListBinaryTag.Builder<CompoundBinaryTag> tree = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (int i = 1; i < layers.size(); i++) {
                Content c = layers.get(i);
                CompoundBinaryTag.Builder entry = CompoundBinaryTag.builder().putInt("parent", parents[i]);
                putLayer(entry, c.diff(), c.before(), c.time(), c.weather(), c.user());
                tree.add(entry.build());
            }
            out.put("tree", tree.build());
        }
        return Nbt.writeBytes(out.build(), BinaryTagIO.Compression.GZIP);
    }

    /** A loaded tree layer: the fresh shard plus its saved user data ({@link #NAME} names it, when saved). */
    public record TreeLayer(@NotNull Shard shard, @NotNull UserData.View data) {}

    /** Rebuilds a {@link #saveTree tree file} as fresh server-ticked shards over {@code base}; layer 0 = the root. */
    public static @NotNull List<TreeLayer> loadTree(@NotNull Instance base, byte @NotNull [] data) {
        ParsedTree parsed = parseTree(data); // parse everything first: a corrupt file constructs nothing
        return build(parsed, new Shard(base));
    }

    /** {@link #loadTree(Instance, byte[])} grafting the file's root as a child layer of {@code parent}. */
    public static @NotNull List<TreeLayer> loadTree(@NotNull Shard parent, byte @NotNull [] data) {
        ParsedTree parsed = parseTree(data);
        return build(parsed, new Shard(parent));
    }

    private static List<TreeLayer> build(ParsedTree parsed, Shard root) {
        List<TreeLayer> out = new ArrayList<>();
        List<Shard> shards = new ArrayList<>();
        for (int i = 0; i < parsed.layers().size(); i++) {
            Content c = parsed.layers().get(i);
            Shard shard = i == 0 ? root : new Shard(shards.get(parsed.parents()[i]));
            apply(shard, c);
            shards.add(shard);
            out.add(new TreeLayer(shard, new UserData.View(c.user())));
        }
        return List.copyOf(out);
    }

    private record ParsedTree(int minY, int maxY, List<Content> layers, int[] parents) {}

    private static ParsedTree parseTree(byte[] data) {
        CompoundBinaryTag root = read(data);
        int minY = root.getInt("minY"), maxY = root.getInt("maxY");
        List<Content> layers = new ArrayList<>();
        List<Integer> parents = new ArrayList<>();
        layers.add(layer(root, minY, maxY));
        parents.add(-1);
        for (BinaryTag tag : root.getList("tree", BinaryTagTypes.COMPOUND)) {
            CompoundBinaryTag entry = (CompoundBinaryTag) tag;
            int parent = entry.getInt("parent");
            if (parent < 0 || parent >= layers.size()) {
                throw new IllegalArgumentException("tree entry parent " + parent + " out of order");
            }
            layers.add(layer(entry, minY, maxY));
            parents.add(parent);
        }
        int[] parentArr = new int[parents.size()];
        for (int i = 0; i < parentArr.length; i++) parentArr[i] = parents.get(i);
        return new ParsedTree(minY, maxY, layers, parentArr);
    }

    /** A {@link #saveTree tree file} exploded into per-layer single files ({@link #PARENT} hints filled from each parent's {@link #NAME}); {@link #join} inverts. */
    public static @NotNull List<byte[]> split(byte @NotNull [] tree) {
        ParsedTree parsed = parseTree(tree);
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < parsed.layers().size(); i++) {
            Content c = parsed.layers().get(i);
            CompoundBinaryTag user = c.user();
            if (i > 0 && name(parsed.layers().get(parsed.parents()[i]).user()) instanceof String parentName) {
                user = CompoundBinaryTag.builder().put(user).putString(PARENT.id(), parentName).build();
            }
            out.add(new Content(parsed.minY(), parsed.maxY(), c.diff(), c.before(), c.time(), c.weather(),
                    user, null).encode());
        }
        return out;
    }

    /**
     * {@link #split}'s inverse: single-layer files back into one tree file, in ANY order - the one file
     * WITHOUT a {@link #PARENT} hint is the root, every other layer's hint must name another layer's
     * {@link #NAME}. File names never matter (the hints live inside the files); unnamed parents, unresolvable
     * hints, duplicate names and zero-or-many roots all throw. The consumed hint becomes the structural
     * parent index.
     */
    public static byte @NotNull [] join(@NotNull List<byte[]> layers) {
        if (layers.isEmpty()) throw new IllegalArgumentException("no layers");
        List<Content> contents = new ArrayList<>();
        for (byte[] file : layers) {
            Content c = decode(file);
            if (c.tree() != null) throw new IllegalArgumentException("layer " + contents.size() + " is already a tree file");
            contents.add(c);
        }
        Map<String, Integer> byName = new HashMap<>();
        Integer root = null;
        for (int i = 0; i < contents.size(); i++) {
            if (contents.get(i).minY() != contents.get(0).minY() || contents.get(i).maxY() != contents.get(0).maxY()) {
                throw new IllegalArgumentException("layer " + i + " has different dimension bounds");
            }
            String name = name(contents.get(i).user());
            if (name != null && byName.putIfAbsent(name, i) != null) {
                throw new IllegalArgumentException("duplicate layer name '" + name + "'");
            }
            if (string(contents.get(i).user(), PARENT.id()) == null) {
                if (root != null) throw new IllegalArgumentException("two roots (no parent hint): layers " + root + " and " + i);
                root = i;
            }
        }
        if (root == null) throw new IllegalArgumentException("no root layer (every file has a parent hint)");
        // stable topological order: keeps split's output order, accepts any other
        int[] placedAt = new int[contents.size()];
        Arrays.fill(placedAt, -1);
        List<Content> resolved = new ArrayList<>();
        int[] parents = new int[contents.size()];
        placedAt[root] = 0;
        parents[0] = -1;
        resolved.add(contents.get(root));
        while (resolved.size() < contents.size()) {
            int before = resolved.size();
            for (int i = 0; i < contents.size(); i++) {
                if (placedAt[i] >= 0) continue;
                Content c = contents.get(i);
                String hint = string(c.user(), PARENT.id());
                Integer parent = byName.get(hint);
                if (parent == null) {
                    throw new IllegalArgumentException("layer " + i + " has an unresolvable parent hint '" + hint
                            + "' - name the layers");
                }
                if (placedAt[parent] < 0) continue; // parent not placed yet, later pass
                placedAt[i] = resolved.size();
                parents[placedAt[i]] = placedAt[parent];
                resolved.add(new Content(c.minY(), c.maxY(), c.diff(), c.before(), c.time(), c.weather(),
                        Nbt.without(c.user(), PARENT.id()), null));
            }
            if (resolved.size() == before) throw new IllegalArgumentException("parent hints form a cycle");
        }
        return encodeTree(contents.get(root).minY(), contents.get(root).maxY(), resolved, parents);
    }

    /**
     * The UNDO of a chronological run of commit saves (oldest first, each {@link #save(Shard, Consumer,
     * boolean) saved} with {@code withBefore} AFTER the previous commit landed): ONE file whose diff restores
     * what the run covered - load it over the current world as a single shard to view the rollback, or
     * commit it to apply. Pure bytes; blocks only (sky/user data don't roll back). Positions a commit
     * couldn't read (unloaded chunks at save time) are skipped.
     */
    public static byte @NotNull [] rollbackChain(@NotNull List<byte[]> commits) {
        if (commits.isEmpty()) throw new IllegalArgumentException("no commits");
        Content first = decode(commits.get(0));
        Map<Point, Block> undo = new HashMap<>();
        for (int i = 0; i < commits.size(); i++) {
            Content c = i == 0 ? first : decode(commits.get(i));
            if (c.minY() != first.minY() || c.maxY() != first.maxY()) {
                throw new IllegalArgumentException("commit " + i + " has different dimension bounds");
            }
            Map<Point, Block> before = c.before();
            if (before == null) throw new IllegalArgumentException("commit " + i + " has no before column - save withBefore");
            // the FIRST commit to touch a position knows its pre-run state
            before.forEach(undo::putIfAbsent);
        }
        return new Content(first.minY(), first.maxY(), undo, null, -1, null, CompoundBinaryTag.empty(), null).encode();
    }

    /** {@link #flattenChain(byte[], int)} of the tree's sole leaf; a branching tree throws, naming the leaves. */
    public static byte @NotNull [] flattenChain(byte @NotNull [] tree) {
        ParsedTree parsed = parseTree(tree);
        boolean[] hasChild = new boolean[parsed.layers().size()];
        for (int p : parsed.parents()) {
            if (p >= 0) hasChild[p] = true;
        }
        List<Integer> leaves = new ArrayList<>();
        for (int i = 0; i < hasChild.length; i++) {
            if (!hasChild[i]) leaves.add(i);
        }
        if (leaves.size() != 1) {
            StringBuilder named = new StringBuilder();
            for (int leaf : leaves) {
                String name = name(parsed.layers().get(leaf).user());
                named.append(named.isEmpty() ? "" : ", ").append(leaf).append(name != null ? " '" + name + "'" : "");
            }
            throw new IllegalArgumentException("branching tree - flatten per leaf: " + named);
        }
        return flattenChain(parsed, leaves.get(0), leafWins());
    }

    /** PURE file-level chain flatten to layer {@code leaf}: diff override root-first, sky nearest-wins, user sections leaf-wins-per-key. */
    public static byte @NotNull [] flattenChain(byte @NotNull [] tree, int leaf) {
        return flattenChain(tree, leaf, leafWins());
    }

    /** {@link #flattenChain(byte[], int)} folding the chain's user sections root-first through {@code userMerge}. */
    public static byte @NotNull [] flattenChain(byte @NotNull [] tree, int leaf,
                                                @NotNull BinaryOperator<CompoundBinaryTag> userMerge) {
        return flattenChain(parseTree(tree), leaf, userMerge);
    }

    private static BinaryOperator<CompoundBinaryTag> leafWins() {
        return (below, above) -> CompoundBinaryTag.builder().put(below).put(above).build();
    }

    private static byte[] flattenChain(ParsedTree parsed, int leaf, BinaryOperator<CompoundBinaryTag> userMerge) {
        if (leaf < 0 || leaf >= parsed.layers().size()) throw new IllegalArgumentException("no layer " + leaf);
        List<Content> chain = new ArrayList<>(); // root first
        for (int i = leaf; i >= 0; i = parsed.parents()[i]) chain.add(0, parsed.layers().get(i));
        Map<Point, Block> diff = new HashMap<>();
        long time = -1;
        Weather weather = null;
        CompoundBinaryTag user = CompoundBinaryTag.empty();
        for (Content layer : chain) {
            diff.putAll(layer.diff());
            if (layer.time() >= 0) time = layer.time();
            if (layer.weather() != null) weather = layer.weather();
            user = userMerge.apply(user, layer.user());
        }
        return new Content(parsed.minY(), parsed.maxY(), diff, null, time, weather, user, null).encode();
    }

    private static @Nullable String name(CompoundBinaryTag user) {
        return string(user, NAME.id());
    }

    private static @Nullable String string(CompoundBinaryTag tag, String key) {
        return tag.get(key) instanceof StringBinaryTag s ? s.value() : null;
    }

    // palette strings stay nbt-less (twins share the entry); NBT/handler detail is a sparse per-entry section
    private static void putNbt(CompoundBinaryTag.Builder root, List<Map.Entry<Point, Block>> entries) {
        ListBinaryTag.Builder<CompoundBinaryTag> out = null;
        for (int i = 0; i < entries.size(); i++) {
            Block block = entries.get(i).getValue();
            BlockHandler handler = block.handler();
            if (!block.hasNbt() && handler == null) continue;
            if (out == null) out = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            CompoundBinaryTag.Builder entry = CompoundBinaryTag.builder().putInt("i", i);
            if (handler != null) entry.putString("handler", handler.getKey().asString());
            if (block.hasNbt()) entry.put("nbt", block.nbt());
            out.add(entry.build());
        }
        if (out != null) root.put("nbt", out.build());
    }

    private static void putSky(CompoundBinaryTag.Builder root, long time, @Nullable Weather weather) {
        if (time < 0 && weather == null) return;
        root.put("sky", Nbt.sky(time, weather));
    }

    // palettes up to 255 entries (the norm) store one BYTE per block; 0xFF = the no-before sentinel
    private static void putColumn(CompoundBinaryTag.Builder root, String key, int[] column, int paletteSize) {
        if (paletteSize > 255) {
            root.putIntArray(key, column);
            return;
        }
        byte[] packed = new byte[column.length];
        for (int i = 0; i < column.length; i++) packed[i] = column[i] == NO_BEFORE ? (byte) 0xFF : (byte) column[i];
        root.putByteArray(key, packed);
    }

    private static int[] column(CompoundBinaryTag root, String key) {
        byte[] packed = root.getByteArray(key);
        if (packed.length == 0) return root.getIntArray(key);
        int[] out = new int[packed.length];
        for (int i = 0; i < packed.length; i++) {
            int v = packed[i] & 0xFF;
            out[i] = v == 0xFF ? NO_BEFORE : v;
        }
        return out;
    }

    /**
     * Applies a saved diff + sky overrides onto {@code shard} (one batched {@code setBlocks}, merging over
     * anything there) and returns the typed user-data view. Nothing mutates until the whole file parses - a
     * corrupt file (or one saved on a different Minecraft version) throws and leaves the shard untouched.
     * Tree files throw - {@link #loadTree} rebuilds those.
     */
    public static @NotNull UserData.View load(@NotNull Shard shard, byte @NotNull [] data) {
        return load(shard, decode(data));
    }

    private static void apply(Shard shard, Content content) {
        DimensionType dim = shard.dimension();
        Map<Point, Block> diff = content.diff();
        if (content.minY() < dim.minY() || content.maxY() > dim.maxY()) { // saved under a taller dimension
            diff = new HashMap<>(diff);
            diff.keySet().removeIf(pos -> pos.blockY() < dim.minY() || pos.blockY() >= dim.maxY());
        }
        shard.setBlocks(diff);
        if (content.time() >= 0) shard.setTime(content.time());
        if (content.weather() != null) shard.setWeather(content.weather());
    }

    private static void applyNbt(CompoundBinaryTag root, int[] xs, int[] ys, int[] zs, Map<Point, Block> diff) {
        for (BinaryTag tag : root.getList("nbt", BinaryTagTypes.COMPOUND)) {
            CompoundBinaryTag entry = (CompoundBinaryTag) tag;
            int i = entry.getInt("i");
            if (i < 0 || i >= xs.length) throw new IllegalArgumentException("corrupt shard file: nbt index " + i);
            Point at = new Vec(xs[i], ys[i], zs[i]);
            Block block = diff.get(at);
            if (block == null) continue;
            if (entry.get("handler") != null) {
                block = block.withHandler(MinecraftServer.getBlockManager().getHandlerOrDummy(entry.getString("handler")));
            }
            if (entry.get("nbt") != null) block = block.withNbt(entry.getCompound("nbt"));
            diff.put(at, block);
        }
    }

    /** The saved {@code before} column ({@link #save(Shard, Consumer, boolean)}); empty when absent/unknown. */
    public static @NotNull Map<Point, Block> before(byte @NotNull [] data) {
        Map<Point, Block> before = decode(data).before();
        return before != null ? before : Map.of();
    }

    /** Decompressed ceiling: op logs run tens of MB; past this it's a gzip bomb, not a shard file. */
    private static final long MAX_DECODED_BYTES = 1L << 30;

    private static CompoundBinaryTag read(byte[] data) {
        CompoundBinaryTag root;
        try {
            root = BinaryTagIO.reader(MAX_DECODED_BYTES).read(new ByteArrayInputStream(data), BinaryTagIO.Compression.GZIP);
        } catch (IOException e) {
            throw new UncheckedIOException("not a shard file?", e);
        }
        if (root.getInt("v") != FORMAT) throw new IllegalArgumentException("unknown shard file version " + root.getInt("v"));
        return root;
    }

    private static int checkedIndex(int id, int size) {
        if (id < 0 || id >= size) throw new IllegalArgumentException("corrupt shard file: palette index " + id);
        return id;
    }

    private static Block[] parsePalette(CompoundBinaryTag root) {
        ListBinaryTag entries = root.getList("palette", BinaryTagTypes.STRING);
        Block[] palette = new Block[entries.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = Nbt.parseState(entries.getString(i));
        return palette;
    }

}
