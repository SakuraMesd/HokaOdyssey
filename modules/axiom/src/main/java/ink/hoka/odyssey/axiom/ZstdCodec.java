package ink.hoka.odyssey.axiom;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * zstd: распаковка туннеля и блок-сущности Axiom (CompressedBlockEntity: VarInt исходный размер, byte словарь (0),
 * byte[] — NBT с безымянным корнем, сжатый со словарём block_entities_v1.dict из AxiomPaperPlugin, MIT).
 */
final class ZstdCodec {

    private ZstdCodec() {}

    private static volatile ZstdDictCompress dictC;
    private static volatile ZstdDictDecompress dictD;

    /** Поднять zstd-jni (нативная библиотека из jar модуля) и словарь. Ошибка — исключение. */
    static void init() throws IOException {
        byte[] dict;
        try (InputStream in = ZstdCodec.class.getResourceAsStream("/zstd_dictionaries/block_entities_v1.dict")) {
            if (in == null) throw new IOException("нет zstd_dictionaries/block_entities_v1.dict в jar");
            dict = in.readAllBytes();
        }
        dictC = new ZstdDictCompress(dict, Zstd.defaultCompressionLevel());
        dictD = new ZstdDictDecompress(dict);
    }

    static boolean ready() { return dictD != null; }

    /** Выключение модуля: словари не закрываем вручную (может идти разбор в другом потоке) — освободит сборщик. */
    static void close() {
        dictC = null;
        dictD = null;
    }

    /** Тело туннеля, сжатое без словаря. size — заявленный клиентом исходный размер (уже проверен по пределу). */
    static byte[] decompress(byte[] src, int off, int len, int size) {
        byte[] dst = new byte[size];
        long r = Zstd.decompressByteArray(dst, 0, size, src, off, len);
        if (Zstd.isError(r)) throw new IllegalStateException("zstd: " + Zstd.getErrorName(r));
        if (r != size) throw new IllegalStateException("zstd: размер " + r + " вместо " + size);
        return dst;
    }

    /** Прочитать CBE из буфера и распаковать в NBT (null — не тот словарь или больше maxBytes). */
    static CompoundBinaryTag readCbe(Buf b, int maxBytes, boolean decode) throws IOException {
        int size = b.readVarInt();
        byte dict = b.readByte();
        byte[] data = b.readByteArray(8 * 1024 * 1024);
        if (!decode || dict != 0 || size < 0 || size > maxBytes) return null;
        byte[] nbt = Zstd.decompress(data, dictD, size);
        return BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(nbt));
    }

    /** Записать NBT как CBE. */
    static void writeCbe(Out o, CompoundBinaryTag tag) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(tag, bo);
        byte[] raw = bo.toByteArray();
        byte[] comp = Zstd.compress(raw, dictC);
        o.writeVarInt(raw.length);
        o.writeByte(0);
        o.writeByteArray(comp);
    }
}
