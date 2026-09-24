#include "Mesh.h"

#include <cmath>

namespace es {

uint32_t MeshData::addVertex(const Vec3& p, const Vec3& n) {
    vertices.insert(vertices.end(), {p.x, p.y, p.z, n.x, n.y, n.z});
    return vertexCount() - 1;
}

void MeshData::append(const MeshData& o, const Mat4& m) {
    uint32_t base = vertexCount();
    for (size_t i = 0; i + 5 < o.vertices.size(); i += 6) {
        Vec3 p = m.transformPoint({o.vertices[i], o.vertices[i + 1], o.vertices[i + 2]});
        Vec3 n{o.vertices[i + 3], o.vertices[i + 4], o.vertices[i + 5]};
        Vec3 nt{m.at(0, 0) * n.x + m.at(0, 1) * n.y + m.at(0, 2) * n.z,
                m.at(1, 0) * n.x + m.at(1, 1) * n.y + m.at(1, 2) * n.z,
                m.at(2, 0) * n.x + m.at(2, 1) * n.y + m.at(2, 2) * n.z};
        addVertex(p, normalize(nt));
    }
    for (uint32_t idx : o.indices) indices.push_back(base + idx);
}

void MeshData::bounds(Vec3& mn, Vec3& mx) const {
    mn = {1e9f, 1e9f, 1e9f};
    mx = {-1e9f, -1e9f, -1e9f};
    for (size_t i = 0; i + 5 < vertices.size(); i += 6) {
        mn.x = std::min(mn.x, vertices[i]); mx.x = std::max(mx.x, vertices[i]);
        mn.y = std::min(mn.y, vertices[i + 1]); mx.y = std::max(mx.y, vertices[i + 1]);
        mn.z = std::min(mn.z, vertices[i + 2]); mx.z = std::max(mx.z, vertices[i + 2]);
    }
}

namespace meshgen {

std::vector<Vec2> circleProfile(float radius, int segs, float phase) {
    std::vector<Vec2> p(segs);
    for (int i = 0; i < segs; ++i) {
        float a = phase + kTwoPi * i / segs;
        p[i] = {radius * std::cos(a), radius * std::sin(a)};
    }
    return p;
}

MeshData extrudeStarX(const std::vector<Vec2>& poly, float x0, float x1) {
    MeshData m;
    const int n = static_cast<int>(poly.size());
    // 側面 (面ごとに法線を分けるため頂点を複製)
    for (int i = 0; i < n; ++i) {
        const Vec2& a = poly[i];
        const Vec2& b = poly[(i + 1) % n];
        Vec3 e{0, b.x - a.x, b.y - a.y};
        Vec3 nrm = normalize(Vec3{0, e.z, -e.y});
        // 反時計回り (YZ 平面, +X から見て) を仮定して外向き法線
        uint32_t i0 = m.addVertex({x0, a.x, a.y}, nrm);
        uint32_t i1 = m.addVertex({x0, b.x, b.y}, nrm);
        uint32_t i2 = m.addVertex({x1, b.x, b.y}, nrm);
        uint32_t i3 = m.addVertex({x1, a.x, a.y}, nrm);
        m.addTri(i0, i1, i2);
        m.addTri(i0, i2, i3);
    }
    // キャップ (中心からの扇)
    for (int side = 0; side < 2; ++side) {
        float x = side ? x1 : x0;
        Vec3 nrm{side ? 1.0f : -1.0f, 0, 0};
        uint32_t c = m.addVertex({x, 0, 0}, nrm);
        uint32_t first = m.vertexCount();
        for (int i = 0; i < n; ++i) m.addVertex({x, poly[i].x, poly[i].y}, nrm);
        for (int i = 0; i < n; ++i) {
            uint32_t a = first + i, b = first + (i + 1) % n;
            if (side) m.addTri(c, a, b); else m.addTri(c, b, a);
        }
    }
    return m;
}

MeshData extrudeRingX(const std::vector<Vec2>& inner, const std::vector<Vec2>& outer, float x0, float x1) {
    MeshData m;
    const int n = static_cast<int>(outer.size());
    auto side = [&](const std::vector<Vec2>& poly, bool out) {
        for (int i = 0; i < n; ++i) {
            const Vec2& a = poly[i];
            const Vec2& b = poly[(i + 1) % n];
            Vec3 nrm = normalize(Vec3{0, b.y - a.y, -(b.x - a.x)});
            if (!out) nrm = -nrm;
            uint32_t i0 = m.addVertex({x0, a.x, a.y}, nrm);
            uint32_t i1 = m.addVertex({x0, b.x, b.y}, nrm);
            uint32_t i2 = m.addVertex({x1, b.x, b.y}, nrm);
            uint32_t i3 = m.addVertex({x1, a.x, a.y}, nrm);
            if (out) { m.addTri(i0, i1, i2); m.addTri(i0, i2, i3); }
            else { m.addTri(i0, i2, i1); m.addTri(i0, i3, i2); }
        }
    };
    side(outer, true);
    side(inner, false);
    for (int s = 0; s < 2; ++s) {
        float x = s ? x1 : x0;
        Vec3 nrm{s ? 1.0f : -1.0f, 0, 0};
        uint32_t base = m.vertexCount();
        for (int i = 0; i < n; ++i) {
            m.addVertex({x, inner[i].x, inner[i].y}, nrm);
            m.addVertex({x, outer[i].x, outer[i].y}, nrm);
        }
        for (int i = 0; i < n; ++i) {
            uint32_t a = base + 2 * i, b = base + 2 * i + 1;
            uint32_t c = base + 2 * ((i + 1) % n), d = base + 2 * ((i + 1) % n) + 1;
            if (s) { m.addTri(a, b, d); m.addTri(a, d, c); }
            else { m.addTri(a, d, b); m.addTri(a, c, d); }
        }
    }
    return m;
}

MeshData cylinderX(float radius, float x0, float x1, int segs) {
    // 側面は滑らかな法線にする
    MeshData m;
    for (int i = 0; i <= segs; ++i) {
        float a = kTwoPi * i / segs;
        Vec3 n{0, std::cos(a), std::sin(a)};
        m.addVertex({x0, radius * n.y, radius * n.z}, n);
        m.addVertex({x1, radius * n.y, radius * n.z}, n);
    }
    for (int i = 0; i < segs; ++i) {
        uint32_t a = 2 * i, b = a + 1, c = a + 2, d = a + 3;
        m.addTri(a, c, d);
        m.addTri(a, d, b);
    }
    for (int s = 0; s < 2; ++s) {
        float x = s ? x1 : x0;
        Vec3 nrm{s ? 1.0f : -1.0f, 0, 0};
        uint32_t c = m.addVertex({x, 0, 0}, nrm);
        uint32_t first = m.vertexCount();
        for (int i = 0; i < segs; ++i) {
            float a = kTwoPi * i / segs;
            m.addVertex({x, radius * std::cos(a), radius * std::sin(a)}, nrm);
        }
        for (int i = 0; i < segs; ++i) {
            uint32_t a = first + i, b = first + (i + 1) % segs;
            if (s) m.addTri(c, a, b); else m.addTri(c, b, a);
        }
    }
    return m;
}

// X 軸系メッシュを Y 軸系へ回す (x→y, y→z, z→x の巡回置換)
static MeshData toY(const MeshData& mx) {
    Mat4 r = Mat4::identity();
    r.at(0, 0) = 0; r.at(0, 1) = 0; r.at(0, 2) = 1;
    r.at(1, 0) = 1; r.at(1, 1) = 0; r.at(1, 2) = 0;
    r.at(2, 0) = 0; r.at(2, 1) = 1; r.at(2, 2) = 0;
    MeshData out;
    out.append(mx, r);
    return out;
}

MeshData cylinderY(float radius, float y0, float y1, int segs) { return toY(cylinderX(radius, y0, y1, segs)); }

MeshData tubeX(float rIn, float rOut, float x0, float x1, int segs) {
    return extrudeRingX(circleProfile(rIn, segs), circleProfile(rOut, segs), x0, x1);
}

MeshData tubeY(float rIn, float rOut, float y0, float y1, int segs) { return toY(tubeX(rIn, rOut, y0, y1, segs)); }

MeshData box(const Vec3& c, const Vec3& s) {
    MeshData m;
    Vec3 h = s * 0.5f;
    const Vec3 n[6] = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    for (const Vec3& f : n) {
        Vec3 u = std::fabs(f.x) > 0.5f ? Vec3{0, 1, 0} : Vec3{1, 0, 0};
        Vec3 v = cross(f, u);
        Vec3 fc{c.x + f.x * h.x, c.y + f.y * h.y, c.z + f.z * h.z};
        Vec3 uu{u.x * h.x, u.y * h.y, u.z * h.z}, vv{v.x * h.x, v.y * h.y, v.z * h.z};
        uint32_t a = m.addVertex(fc - uu - vv, f);
        uint32_t b = m.addVertex(fc + uu - vv, f);
        uint32_t cc = m.addVertex(fc + uu + vv, f);
        uint32_t d = m.addVertex(fc - uu + vv, f);
        // u x v = f となる向きなので (a,b,c) は外向き反時計回り
        m.addTri(a, b, cc);
        m.addTri(a, cc, d);
    }
    return m;
}

std::vector<Vec2> gearProfile(float radius, int teeth, float depth, int steps) {
    std::vector<Vec2> p;
    int n = teeth * steps;
    for (int i = 0; i < n; ++i) {
        float a = kTwoPi * i / n;
        float u = static_cast<float>(i % steps) / steps;  // 歯 1 ピッチ内の位置
        // 台形の歯: 山 40%, 谷 40%, 斜面 10%ずつ
        float h;
        if (u < 0.1f) h = u / 0.1f;
        else if (u < 0.5f) h = 1.0f;
        else if (u < 0.6f) h = 1.0f - (u - 0.5f) / 0.1f;
        else h = 0.0f;
        float r = radius - depth * 0.5f + depth * h;
        p.push_back({r * std::cos(a), r * std::sin(a)});
    }
    return p;
}

MeshData connectingRod(float L, float bigR, float smallR, float width, float t) {
    MeshData m;
    m.append(tubeX(bigR * 0.55f, bigR, -width * 0.5f, width * 0.5f, 28), Mat4::identity());
    m.append(tubeX(smallR * 0.5f, smallR, -width * 0.4f, width * 0.4f, 20), Mat4::translate({0, L, 0}));
    // ビーム (I 断面を矩形で近似)
    float y0 = bigR, y1 = L - smallR;
    m.append(box({0, 0.5f * (y0 + y1), 0}, {width * 0.6f, y1 - y0, t}), Mat4::identity());
    return m;
}

}  // namespace meshgen
}  // namespace es
