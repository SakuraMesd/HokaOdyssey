package ink.hoka.odyssey.client.model;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Поза модели: матрица каждой кости в пикселях модели. Правила — как в Blockbench/ModelEngine:
 * M = M_родителя · T(пивот + сдвиг) · R(поворот кости + анимация) · S · T(−пивот), порядок осей ZYX. Ключи
 * анимации прибавляются как есть (Blockbench 5.0); файлы старее приводятся к этому при загрузке ({@link BbModel}).
 */
public final class Pose {

    private Pose() {}

    /** {@code anim == null} или {@code t < 0} — поза по умолчанию. */
    public static void compute(BbModel model, BbAnimation anim, float t, Matrix4f[] out) {
        compute(model, anim, t, out, new Scratch());
    }

    /** 0.84 Рабочие объекты расчёта — чтобы каждый кадр ничего не создавать (один на поток). */
    public static final class Scratch {
        final Matrix4f identity = new Matrix4f();
        final Vector3f rot = new Vector3f(), pos = new Vector3f(), scl = new Vector3f(), tmp = new Vector3f();
    }

    /** То же без новых объектов: {@code out} заполнен заранее (или создаётся при первом вызове). */
    public static void compute(BbModel model, BbAnimation anim, float t, Matrix4f[] out, Scratch s) {
        for (BbModel.Bone root : model.roots) walk(root, s.identity, anim, t, out, s.rot, s.pos, s.scl, s.tmp);
    }

    private static void walk(BbModel.Bone b, Matrix4f parent, BbAnimation anim, float t, Matrix4f[] out,
                             Vector3f rot, Vector3f pos, Vector3f scl, Vector3f tmp) {
        rot.set(b.rotation);
        pos.set(0, 0, 0);
        scl.set(1, 1, 1);
        BbAnimation.Track tr = anim != null && t >= 0 ? anim.tracks[b.index] : null;
        if (tr != null) {
            if (BbAnimation.sample(tr.rot, t, tmp)) rot.add(tmp);   // формат 5.0: как есть (старые файлы переведены при загрузке)
            if (BbAnimation.sample(tr.pos, t, tmp)) pos.set(tmp);   // ME/Blockbench: сдвиг как есть (база LittleRoom: x −16 + 16)
            if (BbAnimation.sample(tr.scale, t, tmp)) scl.set(tmp);
        }
        Matrix4f m = out[b.index];
        if (m == null) m = out[b.index] = new Matrix4f();
        m.set(parent).translate(b.origin.x + pos.x, b.origin.y + pos.y, b.origin.z + pos.z);
        BbModel.rotateZYX(m, rot);
        if (scl.x != 1 || scl.y != 1 || scl.z != 1) m.scale(scl.x, scl.y, scl.z);
        m.translate(-b.origin.x, -b.origin.y, -b.origin.z);
        for (BbModel.Bone c : b.children) walk(c, m, anim, t, out, rot, pos, scl, tmp);
    }
}
