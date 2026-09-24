#include "Scene.h"

#include <algorithm>
#include <cmath>

namespace es {

namespace mat {
Material castIron() { return {{0.30f, 0.30f, 0.32f}, 0.85f, 0.62f, 0.0f}; }
Material aluminium() { return {{0.86f, 0.87f, 0.89f}, 1.0f, 0.28f, 0.25f}; }
Material castAluminium() { return {{0.63f, 0.64f, 0.66f}, 1.0f, 0.55f, 0.0f}; }
Material steel() { return {{0.58f, 0.58f, 0.60f}, 1.0f, 0.30f, 0.3f}; }
Material forgedSteel() { return {{0.70f, 0.70f, 0.72f}, 1.0f, 0.22f, 0.6f}; }
Material titanium() { return {{0.62f, 0.60f, 0.57f}, 1.0f, 0.34f, 0.35f}; }
Material copper() { return {{0.95f, 0.62f, 0.50f}, 1.0f, 0.32f, 0.0f}; }
Material brass() { return {{0.90f, 0.75f, 0.42f}, 1.0f, 0.30f, 0.0f}; }
Material blued() { return {{0.32f, 0.30f, 0.36f}, 1.0f, 0.45f, 0.0f}; }
Material nickel() { return {{0.76f, 0.72f, 0.66f}, 1.0f, 0.30f, 0.0f}; }
Material paint(const Vec3& c) { return {c, 0.0f, 0.45f, 0.3f}; }
Material gas() { return {{1.0f, 0.6f, 0.2f}, 0.0f, 1.0f, 0.0f}; }
}  // namespace mat

namespace {

constexpr float kDeg = kPi / 180.0f;

Mat4 rotX(float rad) { return Mat4::rotation(Quat::axisAngle({1, 0, 0}, rad)); }

// シリンダ座標系: 原点 = クランク中心 (x 位置付き), +Y = シリンダ軸, +Z = 横方向
Mat4 cylFrame(const EngineSpec& s, int piston) {
    const PistonDef& pd = s.pistons[piston];
    const ThrowDef& th = s.throws[pd.throwIdx];
    const CrankDef& cr = s.cranks[th.crank];
    return Mat4::translate({th.x + pd.xOffset, cr.y, cr.z}) * rotX(pd.axisDeg * kDeg);
}

Vec3 axisDir(float axisDeg) { return {0, std::cos(axisDeg * kDeg), std::sin(axisDeg * kDeg)}; }
Vec3 lateralDir(float axisDeg) { return {0, -std::sin(axisDeg * kDeg), std::cos(axisDeg * kDeg)}; }

int addNode(Scene& sc, int mesh, const Material& m, Bind b, int index = 0, const Mat4& local = Mat4::identity()) {
    SceneNode n;
    n.mesh = mesh;
    n.mat = m;
    n.bind = b;
    n.index = index;
    n.local = local;
    sc.nodes.push_back(n);
    return static_cast<int>(sc.nodes.size()) - 1;
}

// 任意軸回転ノード
int addSpinner(Scene& sc, int mesh, const Material& m, const Vec3& pivot, const Vec3& axis, float ratio, float phase,
               int crankIdx, int spool, const Mat4& local = Mat4::identity()) {
    int id = addNode(sc, mesh, m, Bind::RotAxis, crankIdx, local);
    SceneNode& n = sc.nodes[id];
    n.pivot = pivot;
    n.axis = axis;
    n.ratio = ratio;
    n.phase = phase;
    n.spool = spool;
    return id;
}

// ブレード付きディスク (軸 X)
MeshData bladedDisc(float hubR, float tipR, int blades, float x0, float x1, float twistDeg, float chordFrac = 0.8f) {
    MeshData m = meshgen::cylinderX(hubR, x0, x1, 28);
    float chord = std::max(0.004f, (x1 - x0) * chordFrac);
    float thick = std::max(0.002f, kTwoPi * hubR / std::max(blades, 1) * 0.25f);
    MeshData blade = meshgen::box({0, 0.5f * (hubR + tipR), 0}, {chord, tipR - hubR, thick});
    for (int i = 0; i < blades; ++i) {
        float a = kTwoPi * i / blades;
        Mat4 t = Mat4::translate({0.5f * (x0 + x1), 0, 0}) * rotX(a) *
                 Mat4::rotation(Quat::axisAngle({0, 1, 0}, twistDeg * kDeg));
        m.append(blade, t);
    }
    return m;
}

void addGearboxAndOutput(const EngineSpec& s, Scene& sc, float x0, float cy, float cz, float scale, bool multiGear) {
    // scale = フライホイール半径程度の代表寸法
    const float C = 0.95f * scale;         // 軸間距離
    const float m = 0.045f * scale;        // モジュール相当
    const float w = 0.16f * scale;         // 歯幅
    const float shaftR = 0.10f * scale;
    const auto& gears = s.drivetrain.gears;
    int ng = multiGear ? static_cast<int>(gears.size()) : 1;
    float inRatio = 1.5f;
    float rIn = C / (1.0f + inRatio), rCs0 = C - rIn;
    auto teeth = [&](float r) { return std::max(8, static_cast<int>(std::round(2.0f * r / m))); };
    int zIn = teeth(rIn), zCs0 = teeth(rCs0);
    float len = (ng + 2) * (w * 1.8f);
    float xEnd = x0 + len;
    Vec3 csPivot{0, cy - C, cz};

    // ハウジング
    int hb = sc.addMesh(meshgen::box({0.5f * (x0 + xEnd), cy - 0.45f * C, cz}, {len + 0.1f * scale, 3.0f * C, 1.9f * C}));
    int n = addNode(sc, hb, mat::castAluminium(), Bind::Static);
    sc.nodes[n].housing = true;
    sc.nodes[n].sectionCut = true;
    sc.nodes[n].heat = HeatSrc::Oil;
    sc.nodes[n].engineBody = false;

    // 入力軸 + 常時噛合ギア
    MeshData inShaft = meshgen::cylinderX(shaftR, x0 - 0.2f * scale, x0 + w * 1.8f, 20);
    inShaft.append(meshgen::extrudeStarX(meshgen::gearProfile(rIn, zIn, 2.2f * m), x0 + 0.3f * w, x0 + 1.3f * w), Mat4::identity());
    int mi = sc.addMesh(inShaft);
    n = addSpinner(sc, mi, mat::steel(), {0, cy, cz}, {1, 0, 0}, 1.0f, 0, 0, -1);
    sc.nodes[n].engineBody = false;
    // カウンターシャフト (全ギア一体)
    MeshData cs = meshgen::cylinderX(shaftR, x0, xEnd, 20);
    cs.append(meshgen::extrudeStarX(meshgen::gearProfile(rCs0, zCs0, 2.2f * m), x0 + 0.3f * w, x0 + 1.3f * w), Mat4::identity());
    struct OutGear { float rOut; int zOut, zCs; float x; };
    std::vector<OutGear> og;
    for (int i = 0; i < ng; ++i) {
        float g = multiGear ? gears[i] : gears.empty() ? 1.0f : gears[0];
        // 総減速比 g = inRatio * (rOut/rCs)
        float q = std::max(0.3f, g / inRatio);
        float rCs = C / (1.0f + q), rOut = C - rCs;
        float x = x0 + (i + 1.2f) * w * 1.8f;
        int zCs = teeth(rCs), zOut = teeth(rOut);
        cs.append(meshgen::extrudeStarX(meshgen::gearProfile(rCs, zCs, 2.2f * m), x, x + w), Mat4::identity());
        og.push_back({rOut, zOut, zCs, x});
    }
    int mcs = sc.addMesh(cs);
    // カウンターシャフト角 = -入力角 * zIn/zCs0
    float csRatio = -static_cast<float>(zIn) / zCs0;
    n = addSpinner(sc, mcs, mat::steel(), csPivot, {1, 0, 0}, csRatio, kPi / zCs0, 0, -1, Mat4::translate({0, -(cy - C), -cz}) * Mat4::translate({0, cy - C, cz}));
    sc.nodes[n].local = Mat4::identity();
    sc.nodes[n].engineBody = false;
    // 出力ギア (遊転: カウンターシャフトと常時噛合)
    for (int i = 0; i < ng; ++i) {
        int mg = sc.addMesh(meshgen::extrudeStarX(meshgen::gearProfile(og[i].rOut, og[i].zOut, 2.2f * m), og[i].x, og[i].x + w));
        float ratio = csRatio * -static_cast<float>(og[i].zCs) / og[i].zOut;
        n = addSpinner(sc, mg, mat::steel(), {0, cy, cz}, {1, 0, 0}, ratio, kPi / og[i].zOut, 0, -1);
        sc.nodes[n].gearIndex = multiGear ? i + 1 : 1;
        sc.nodes[n].engineBody = false;
    }
    // 出力軸 + プロペラシャフト + デフ
    float xProp0 = xEnd, xProp1 = xEnd + 1.6f * scale;
    MeshData out = meshgen::cylinderX(shaftR * 0.9f, x0 + w * 1.9f, xProp1, 20);
    // ユニバーサルジョイントのヨーク
    out.append(meshgen::box({xProp0 + 0.15f * scale, 0, 0}, {0.18f * scale, 0.42f * scale, 0.12f * scale}), Mat4::identity());
    out.append(meshgen::box({xProp1 - 0.15f * scale, 0, 0}, {0.18f * scale, 0.12f * scale, 0.42f * scale}), Mat4::identity());
    // 見た目の大きさを抑えるためリング径は上限付き (回転比は実際の最終減速比を使う)
    float rPin = 0.26f * scale, rRing = rPin * std::clamp(s.drivetrain.finalDrive, 1.5f, 2.4f);
    int zPin = teeth(rPin), zRing = teeth(rRing);
    // ピニオン (出力軸先端)
    out.append(meshgen::extrudeStarX(meshgen::gearProfile(rPin, zPin, 2.2f * m), xProp1, xProp1 + w), Mat4::identity());
    int mo = sc.addMesh(out);
    n = addNode(sc, mo, mat::forgedSteel(), Bind::OutputShaft, 0, Mat4::translate({0, cy, cz}));
    sc.nodes[n].engineBody = false;
    // リングギア (軸 Z) + ハーフシャフト
    float xRing = xProp1 + w + rRing * 0.05f;
    MeshData ring = meshgen::extrudeStarX(meshgen::gearProfile(rRing, zRing, 2.2f * m), -0.5f * w, 0.5f * w);
    ring.append(meshgen::cylinderX(shaftR * 0.9f, -1.4f * scale - 0.5f * w, -0.5f * w, 16), Mat4::identity());
    ring.append(meshgen::cylinderX(shaftR * 0.9f, 0.5f * w, 1.4f * scale + 0.5f * w, 16), Mat4::identity());
    int mr = sc.addMesh(ring);
    // 軸 X で作ったリングを Z 軸まわりへ向ける
    Mat4 toZ = Mat4::rotation(Quat::axisAngle({0, 1, 0}, -kPi * 0.5f));
    n = addNode(sc, mr, mat::forgedSteel(), Bind::OutputShaft, 1, toZ);
    sc.nodes[n].pivot = {xRing + rRing * 0.0f, cy, cz + rPin + rRing - 2.2f * m * 0.5f};
    sc.nodes[n].axis = {0, 0, 1};
    sc.nodes[n].ratio = -1.0f / std::max(0.5f, s.drivetrain.finalDrive);
    sc.nodes[n].engineBody = false;
    int db = sc.addMesh(meshgen::cylinderX(rRing * 1.12f, -0.9f * w, 0.9f * w, 32));
    n = addNode(sc, db, mat::castIron(), Bind::Static, 0, Mat4::translate(sc.nodes[n].pivot) * toZ);
    sc.nodes[n].housing = true;
    sc.nodes[n].sectionCut = true;
    sc.nodes[n].engineBody = false;
    sc.outputAnchor = {0.5f * (x0 + xProp1), cy - 0.3f * C, cz};
    sc.outputSize = (xProp1 - x0) * 0.6f + scale;
}

void addPropeller(const EngineSpec& s, Scene& sc, float xFront, float scale) {
    const DrivetrainDef& d = s.drivetrain;
    // 減速ギアケース (静止) + プロペラ軸 + ハブ + ブレード
    int gc = sc.addMesh(meshgen::cylinderX(scale * 0.55f, xFront - scale * 0.6f, xFront, 40));
    int n = addNode(sc, gc, mat::paint({0.22f, 0.24f, 0.22f}), Bind::Static);
    sc.nodes[n].housing = true;
    sc.nodes[n].sectionCut = true;
    float xHub = xFront - scale * 0.9f;
    float bladeLen = std::min(d.propDiameter * 0.5f, scale * 3.2f);  // 画面に収めるため実寸を上限付きに
    MeshData prop = meshgen::cylinderX(scale * 0.12f, xHub, xFront, 20);
    prop.append(meshgen::cylinderX(scale * 0.24f, xHub - scale * 0.15f, xHub + scale * 0.2f, 24), Mat4::identity());
    MeshData blade = meshgen::box({0, 0.5f * bladeLen + scale * 0.2f, 0}, {scale * 0.06f, bladeLen, scale * 0.32f});
    for (int i = 0; i < d.propBlades; ++i) {
        float a = kTwoPi * i / d.propBlades;
        prop.append(blade, Mat4::translate({xHub, 0, 0}) * rotX(a) * Mat4::rotation(Quat::axisAngle({0, 1, 0}, 25 * kDeg)));
    }
    int mp = sc.addMesh(prop);
    n = addSpinner(sc, mp, mat::paint({0.12f, 0.12f, 0.12f}), {0, 0, 0}, {1, 0, 0}, d.propReduction, 0, 0, -1);
    sc.nodes[n].engineBody = false;
    sc.outputAnchor = {xHub, 0, 0};
    sc.outputSize = bladeLen * 1.4f;
}

void addFlywheel(const EngineSpec& s, Scene& sc, float x, float R, float thick, int crank) {
    const CrankDef& cr = s.cranks[crank];
    MeshData fw = meshgen::cylinderX(R, x, x + thick, 48);
    int teethN = 90;
    auto outer = meshgen::gearProfile(R + 0.035f * R + 0.01f, teethN, 0.03f * R + 0.006f, 4);
    auto inner = meshgen::circleProfile(R, static_cast<int>(outer.size()));
    fw.append(meshgen::extrudeRingX(inner, outer, x + thick * 0.1f, x + thick * 0.9f), Mat4::identity());
    int m = sc.addMesh(fw);
    int n = addNode(sc, m, mat::castIron(), Bind::Crank, crank, Mat4::identity());
    sc.nodes[n].pivot = {0, cr.y, cr.z};
}

void buildReciprocating(const EngineSpec& s, Scene& sc) {
    const float b = s.bore;
    const bool isSteam = s.cycle == Cycle::Steam;
    const bool isStirling = s.cycle == Cycle::Stirling;
    const bool radial = !s.pistons.empty() && s.pistons.size() > 1 &&
                        std::any_of(s.pistons.begin(), s.pistons.end(), [](const PistonDef& p) { return p.rod == RodType::Articulated; });
    const bool airCooled = radial || s.id.rfind("i1", 0) == 0 || s.id.rfind("v2", 0) == 0 || s.id.rfind("b2", 0) == 0;
    const float crownH = 0.30f * b, skirtH = 0.25f * b;
    const float steamRodLen = isSteam ? s.stroke * 0.9f + 0.3f * b : 0.0f;

    // --- クランクシャフト ---
    float globalXMin = 1e9f, globalXMax = -1e9f;
    std::vector<float> pinR(s.cranks.size());
    for (size_t c = 0; c < s.cranks.size(); ++c) {
        const CrankDef& cr = s.cranks[c];
        float r = cr.radius;
        float pr = std::clamp(0.5f * r, 0.10f * b, 0.28f * b);
        pinR[c] = pr;
        float mainR = pr * 1.1f;
        float webT = 0.13f * b;
        struct Span { float a, b; };
        std::vector<Span> occupied;
        MeshData crank;
        float cw = 1.15f * r + 0.6f * pr;
        for (size_t t = 0; t < s.throws.size(); ++t) {
            const ThrowDef& th = s.throws[t];
            if (th.crank != static_cast<int>(c)) continue;
            float half = 0.2f * b;
            for (const auto& p : s.pistons)
                if (p.throwIdx == static_cast<int>(t)) half = std::max(half, std::fabs(p.xOffset) + 0.16f * b);
            float a = th.pinAngleDeg * kDeg;
            crank.append(meshgen::cylinderX(pr, th.x - half, th.x + half, 24),
                         Mat4::translate({0, r * std::cos(a), r * std::sin(a)}));
            for (int sd = -1; sd <= 1; sd += 2) {
                float wx = th.x + sd * (half + 0.5f * webT);
                crank.append(meshgen::box({wx, 0.5f * r, 0}, {webT, r + 2.2f * pr, 2.3f * pr}), rotX(a));
                crank.append(meshgen::box({wx, -0.5f * cw, 0}, {webT, cw, 1.9f * r + pr}), rotX(a));
            }
            occupied.push_back({th.x - half - webT, th.x + half + webT});
        }
        std::sort(occupied.begin(), occupied.end(), [](const Span& x, const Span& y) { return x.a < y.a; });
        if (occupied.empty()) continue;
        float xs = occupied.front().a - 0.3f * b, xe = occupied.back().b + 0.1f * b;
        // メインジャーナル (スロー間)
        float cursor = xs;
        for (const Span& sp : occupied) {
            if (sp.a > cursor + 1e-4f) crank.append(meshgen::cylinderX(mainR, cursor, sp.a, 24), Mat4::identity());
            cursor = std::max(cursor, sp.b);
        }
        crank.append(meshgen::cylinderX(mainR, cursor, xe + 0.05f * b, 24), Mat4::identity());
        int mesh = sc.addMesh(crank);
        int n = addNode(sc, mesh, mat::forgedSteel(), Bind::Crank, static_cast<int>(c));
        sc.nodes[n].pivot = {0, cr.y, cr.z};
        sc.nodes[n].stress = StressSrc::Crank;
        sc.nodes[n].heat = HeatSrc::Oil;
        globalXMin = std::min(globalXMin, xs);
        globalXMax = std::max(globalXMax, xe);

        // クランクケース
        float ext = std::max(cw, r + pr) + 0.12f * b;
        int cc;
        if (radial) {
            float rr = 0;
            for (const auto& p : s.pistons) rr = std::max(rr, p.sMin - skirtH - 0.05f * b);
            rr = std::max(rr, ext);
            cc = sc.addMesh(meshgen::cylinderX(rr, xs, xe, 48));
        } else if (s.cranks.size() > 1) {
            cc = sc.addMesh(meshgen::cylinderX(ext, xs, xe, 40));
        } else {
            cc = sc.addMesh(meshgen::box({0.5f * (xs + xe), 0, 0}, {xe - xs, 2 * ext, 2 * ext}));
        }
        n = addNode(sc, cc, isSteam ? mat::paint({0.18f, 0.22f, 0.16f}) : mat::castAluminium(), Bind::Static, 0,
                    Mat4::translate({0, cr.y, cr.z}));
        sc.nodes[n].housing = true;
        sc.nodes[n].sectionCut = true;
        sc.nodes[n].heat = HeatSrc::Oil;
    }
    sc.crankCenter = {0.5f * (globalXMin + globalXMax), s.cranks[0].y, s.cranks[0].z};

    // --- ピストン/ロッド ---
    for (size_t p = 0; p < s.pistons.size(); ++p) {
        const PistonDef& pd = s.pistons[p];
        const int crank = s.throws[pd.throwIdx].crank;
        MeshData pm;
        if (pd.crosshead) {
            pm.append(meshgen::box({0, 0, 0}, {0.36f * b, 0.3f * b, 0.36f * b}), Mat4::identity());
            pm.append(meshgen::cylinderY(0.07f * b, 0.15f * b, steamRodLen, 16), Mat4::identity());
            pm.append(meshgen::cylinderY(0.49f * b, steamRodLen, steamRodLen + 0.22f * b, 40), Mat4::identity());
        } else {
            pm = meshgen::cylinderY(0.485f * b, -skirtH, crownH, 40);
        }
        int pmesh = sc.addMesh(pm);
        int n = addNode(sc, pmesh, pd.crosshead ? mat::steel() : mat::aluminium(), Bind::Piston, static_cast<int>(p));
        sc.nodes[n].sectionCut = true;
        sc.nodes[n].heat = HeatSrc::Piston;
        sc.nodes[n].heatIndex = static_cast<int>(p);
        sc.nodes[n].stress = StressSrc::Piston;
        sc.nodes[n].stressIndex = static_cast<int>(p);

        float bigR = pd.rod == RodType::Articulated ? 0.11f * b : pinR[crank] * 1.35f;
        float width = pd.rod == RodType::Articulated ? 0.16f * b : 0.24f * b;
        MeshData rm = meshgen::connectingRod(pd.rodLength, bigR, 0.13f * b, width, 0.14f * b);
        if (pd.rod == RodType::Master) {
            // ナックルフランジ (アーティキュレーテッドロッドのピンを保持)
            float rk = 0;
            for (const auto& q : s.pistons)
                if (q.master == static_cast<int>(p)) rk = std::max(rk, q.knuckleRadius);
            rm.append(meshgen::tubeX(bigR * 0.95f, rk + 0.14f * b, -0.12f * b, 0.12f * b, 48), Mat4::identity());
            for (const auto& q : s.pistons) {
                if (q.master != static_cast<int>(p)) continue;
                float ka = q.knuckleAngleDeg * kDeg;
                rm.append(meshgen::cylinderX(0.06f * b, -0.16f * b, 0.16f * b, 12),
                          Mat4::translate({0, rk * std::cos(ka), rk * std::sin(ka)}));
            }
        }
        int rmesh = sc.addMesh(rm);
        n = addNode(sc, rmesh, s.redlineRpm > 8000 ? mat::titanium() : mat::forgedSteel(), Bind::Rod, static_cast<int>(p));
        sc.nodes[n].stress = StressSrc::Rod;
        sc.nodes[n].stressIndex = static_cast<int>(p);
    }

    // --- シリンダ/ヘッド/バルブ/ガス柱 ---
    bool firstCyl = true;
    std::vector<float> roofOf(s.chambers.size(), 0.0f);
    for (size_t c = 0; c < s.chambers.size(); ++c) {
        const ChamberDef& ch = s.chambers[c];
        int p0 = ch.pistons.empty() ? -1 : ch.pistons[0];
        if (p0 < 0) continue;
        const PistonDef& pd = s.pistons[p0];
        Mat4 frame = cylFrame(s, p0);
        float hc = std::max(0.01f * b, ch.clearance / std::max(ch.area, 1e-9f));
        if (ch.kind == ChamberKind::SteamCrank) continue;  // 複動の反対側は同じシリンダ

        float y0, y1;
        if (isSteam) {
            y0 = pd.sMin + steamRodLen - 0.08f * b;
            y1 = pd.sMax + steamRodLen + 0.22f * b + hc;
        } else if (ch.pistons.size() == 2) {
            const PistonDef& pb = s.pistons[ch.pistons[1]];
            const CrankDef& ca = s.cranks[s.throws[pd.throwIdx].crank];
            const CrankDef& cb = s.cranks[s.throws[pb.throwIdx].crank];
            float D = length(Vec3{0, cb.y - ca.y, cb.z - ca.z});
            y0 = pd.sMin - skirtH - 0.05f * b;
            y1 = D - (pb.sMin - skirtH - 0.05f * b);
        } else {
            y0 = pd.sMin - skirtH - 0.05f * b;
            y1 = pd.sMax + crownH + hc;
        }
        roofOf[c] = y1;
        MeshData liner = meshgen::tubeY(0.5f * b, 0.62f * b, y0, y1, 40);
        if (airCooled) {
            for (float y = y0 + 0.25f * (y1 - y0); y < y1 - 0.02f * b; y += 0.09f * b)
                liner.append(meshgen::tubeY(0.62f * b, 0.86f * b, y, y + 0.025f * b, 40), Mat4::identity());
        }
        int lm = sc.addMesh(liner);
        Material linerMat = isStirling ? (ch.kind == ChamberKind::StirlingHot ? mat::paint({0.55f, 0.18f, 0.1f}) : mat::paint({0.12f, 0.25f, 0.5f}))
                                       : (isSteam ? mat::paint({0.16f, 0.24f, 0.18f}) : mat::castAluminium());
        int n = addNode(sc, lm, linerMat, Bind::Static, 0, frame);
        sc.nodes[n].housing = true;
        sc.nodes[n].sectionCut = true;
        sc.nodes[n].heat = HeatSrc::Liner;
        sc.nodes[n].heatIndex = static_cast<int>(c);

        if (firstCyl) {
            Vec3 a = axisDir(pd.axisDeg);
            const CrankDef& cr = s.cranks[s.throws[pd.throwIdx].crank];
            Vec3 base{s.throws[pd.throwIdx].x + pd.xOffset, cr.y, cr.z};
            // クランク中心からヘッド上端までを収める
            float lo = -(cr.radius + 0.3f * b), hi = y1 + 0.9f * b;
            if (ch.pistons.size() == 2) lo = y0 - cr.radius;
            sc.cyl1Center = base + a * (0.5f * (lo + hi));
            sc.cyl1Axis = a;
            sc.cyl1Size = (hi - lo);
            sc.sectionX = s.throws[pd.throwIdx].x + pd.xOffset;
            firstCyl = false;
        }

        if (ch.pistons.size() == 1) {
            // ヘッド (蒸気機関はシリンダカバー)
            MeshData head = isSteam ? meshgen::cylinderY(0.62f * b, y1, y1 + 0.1f * b, 40)
                                    : meshgen::box({0, y1 + 0.2f * b, 0}, {1.12f * b, 0.4f * b, 1.3f * b});
            if (isSteam) {
                head.append(meshgen::tubeY(0.08f * b, 0.62f * b, y0 - 0.1f * b, y0, 40), Mat4::identity());
                // 弁室 (スライドバルブチェスト)
                head.append(meshgen::box({0, 0.5f * (y0 + y1), 0.86f * b}, {0.55f * b, y1 - y0, 0.42f * b}), Mat4::identity());
            }
            int hm = sc.addMesh(head);
            n = addNode(sc, hm, isSteam ? mat::paint({0.16f, 0.24f, 0.18f}) : (isStirling ? linerMat : mat::castAluminium()), Bind::Static, 0, frame);
            sc.nodes[n].housing = true;
            sc.nodes[n].sectionCut = true;
            sc.nodes[n].heat = HeatSrc::Head;
            sc.nodes[n].heatIndex = static_cast<int>(c);
            if (isSteam) {
                // スライドバルブ + クロスヘッドガイド
                int sv = sc.addMesh(meshgen::box({0, 0.5f * (y0 + y1), 0.8f * b}, {0.36f * b, 0.35f * b, 0.2f * b}));
                n = addNode(sc, sv, mat::brass(), Bind::SlideValve, p0, frame);
                sc.nodes[n].dir = axisDir(pd.axisDeg);
                sc.nodes[n].sectionCut = true;
                MeshData guides;
                float g0 = pd.sMin - 0.25f * b, g1 = pd.sMax + 0.25f * b;
                guides.append(meshgen::box({0, 0.5f * (g0 + g1), 0.22f * b}, {0.3f * b, g1 - g0, 0.06f * b}), Mat4::identity());
                guides.append(meshgen::box({0, 0.5f * (g0 + g1), -0.22f * b}, {0.3f * b, g1 - g0, 0.06f * b}), Mat4::identity());
                int gm = sc.addMesh(guides);
                addNode(sc, gm, mat::steel(), Bind::Static, 0, frame);
            }
        }

        // バルブ + カム (4 スト・ポペット弁)
        if (s.isFourStroke() && ch.kind == ChamberKind::Combustion && ch.pistons.size() == 1) {
            const ValveTrainDef& vt = s.valves;
            for (int side = 0; side < 2; ++side) {
                int nv = side == 0 ? vt.intakePerCyl : vt.exhaustPerCyl;
                float lat = (side == 0 ? -1.0f : 1.0f) * 0.22f * b;
                float rv = nv >= 2 ? 0.14f * b : 0.19f * b;
                for (int k = 0; k < nv; ++k) {
                    float xo = nv == 1 ? 0.0f : (-0.2f * b + 0.4f * b * k / (nv - 1));
                    MeshData vm = meshgen::cylinderY(rv, y1, y1 + 0.03f * b, 24);
                    vm.append(meshgen::cylinderY(0.028f * b, y1 + 0.03f * b, y1 + 0.5f * b, 10), Mat4::identity());
                    int vmesh = sc.addMesh(vm);
                    n = addNode(sc, vmesh, side == 0 ? mat::steel() : mat::nickel(), Bind::Valve, static_cast<int>(c),
                                frame * Mat4::translate({xo, 0, lat}));
                    sc.nodes[n].sub = side;
                    sc.nodes[n].dir = axisDir(pd.axisDeg);
                    sc.nodes[n].sectionCut = true;
                    sc.nodes[n].heat = side == 0 ? HeatSrc::Head : HeatSrc::Exhaust;
                    sc.nodes[n].heatIndex = static_cast<int>(c);
                    if (sc.followValveNode < 0 && side == 0) {
                        sc.followValveNode = n;
                        sc.followOffset = axisDir(pd.axisDeg) * (0.35f * b) + lateralDir(pd.axisDeg) * (-0.1f * b);
                    }
                }
                if (vt.overhead && s.family == Family::Reciprocating) {
                    // カム: ノーズが弁の最大リフト時に弁側を向くように位相を決める
                    float open = side == 0 ? vt.ivoDeg : vt.evoDeg;
                    float close = side == 0 ? vt.ivcDeg : vt.evcDeg;
                    float dur = static_cast<float>(wrapPos(close - open, 720.0));
                    float thetaPk = ch.firingDeg + open + 0.5f * dur;
                    float nose = pd.axisDeg + 180.0f - 0.5f * thetaPk;
                    float rb = 0.12f * b;
                    float lmax = side == 0 ? vt.liftIn : vt.liftEx;
                    std::vector<Vec2> prof;
                    for (int i = 0; i < 96; ++i) {
                        float ang = 360.0f * i / 96.0f;
                        float d = static_cast<float>(wrapSym(ang - nose, 360.0));
                        float u = (2.0f * d + 0.5f * dur) / dur;
                        float lift = (u > 0 && u < 1) ? lmax * std::pow(std::sin(kPi * u), 2.0f) : 0.0f;
                        float rr = rb + lift;
                        prof.push_back({rr * std::cos(ang * kDeg), rr * std::sin(ang * kDeg)});
                    }
                    const CrankDef& cr = s.cranks[s.throws[pd.throwIdx].crank];
                    float cx = s.throws[pd.throwIdx].x + pd.xOffset;
                    Vec3 pivot = Vec3{cx, cr.y, cr.z} + axisDir(pd.axisDeg) * (y1 + 0.62f * b) + lateralDir(pd.axisDeg) * lat;
                    MeshData cam = meshgen::extrudeStarX(prof, -0.18f * b, 0.18f * b);
                    cam.append(meshgen::cylinderX(0.06f * b, -0.62f * b, 0.62f * b, 12), Mat4::identity());
                    int cm = sc.addMesh(cam);
                    n = addSpinner(sc, cm, mat::forgedSteel(), pivot, {1, 0, 0}, 0.5f * cr.dir, 0, s.throws[pd.throwIdx].crank, -1);
                    (void)n;
                }
            }
        }

        // 燃焼ガス柱
        if (ch.kind == ChamberKind::Combustion || ch.kind == ChamberKind::SteamHead || isStirling) {
            int gm = sc.addMesh(meshgen::cylinderY(0.482f * b, 0.0f, 1.0f, 32));
            n = addNode(sc, gm, mat::gas(), Bind::GasVolume, static_cast<int>(c));
            sc.nodes[n].gas = true;
            sc.nodes[n].sectionCut = true;
            sc.nodes[n].heat = HeatSrc::Gas;
            sc.nodes[n].heatIndex = static_cast<int>(c);
            sc.nodes[n].dir = axisDir(pd.axisDeg);
            sc.nodes[n].sub = ch.pistons.size() == 2 ? 1 : (isSteam ? 2 : 0);
            const CrankDef& cr = s.cranks[s.throws[pd.throwIdx].crank];
            sc.nodes[n].pivot = Vec3{s.throws[pd.throwIdx].x + pd.xOffset, cr.y, cr.z} + axisDir(pd.axisDeg) * y1;
            sc.nodes[n].ratio = crownH + (isSteam ? steamRodLen + 0.22f * b - crownH : 0.0f);
            if (isSteam && s.chambers.size() > c + 1 && s.chambers[c + 1].kind == ChamberKind::SteamCrank) {
                int g2 = addNode(sc, gm, mat::gas(), Bind::GasVolume, static_cast<int>(c + 1));
                sc.nodes[g2] = sc.nodes[n];
                sc.nodes[g2].index = static_cast<int>(c + 1);
                sc.nodes[g2].heatIndex = static_cast<int>(c + 1);
                sc.nodes[g2].sub = 3;
                sc.nodes[g2].pivot = Vec3{s.throws[pd.throwIdx].x + pd.xOffset, cr.y, cr.z} + axisDir(pd.axisDeg) * y0;
                sc.nodes[g2].ratio = steamRodLen;
            }
        }
    }

    // スターリング: 高温/低温ヘッドを結ぶ再生器ダクト
    if (isStirling && s.chambers.size() >= 2) {
        auto headPt = [&](int c) {
            int p = s.chambers[c].pistons[0];
            const PistonDef& pd = s.pistons[p];
            const CrankDef& cr = s.cranks[s.throws[pd.throwIdx].crank];
            return Vec3{s.throws[pd.throwIdx].x + pd.xOffset, cr.y, cr.z} + axisDir(pd.axisDeg) * (roofOf[c] + 0.35f * b);
        };
        Vec3 a = headPt(0), c2 = headPt(1);
        Vec3 d = c2 - a;
        int dm = sc.addMesh(meshgen::cylinderY(0.12f * b, 0, length(d), 20));
        int n = addNode(sc, dm, mat::copper(), Bind::Static, 0, Mat4::trs(a, rotationBetween({0, 1, 0}, d)));
        sc.nodes[n].housing = true;
    }

    // フライホイール/駆動系
    float scale = std::max(1.9f * s.cranks[0].radius + pinR[0], 0.85f * b);
    if (s.drivetrain.propeller) {
        addPropeller(s, sc, globalXMin - 0.1f * b, std::max(scale, 0.9f * b));
    } else {
        float fx = globalXMax + 0.05f * b;
        float thick = 0.25f * scale;
        float fwR = isSteam ? 3.2f * s.cranks[0].radius : scale;
        addFlywheel(s, sc, fx, fwR, isSteam ? 0.35f * b : thick, 0);
        if (s.drivetrain.hasGearbox) {
            addGearboxAndOutput(s, sc, fx + thick + 0.15f * scale, s.cranks[0].y, s.cranks[0].z, std::max(scale, 0.08f),
                                s.drivetrain.gears.size() > 1);
        } else {
            sc.outputAnchor = {fx, s.cranks[0].y, s.cranks[0].z};
            sc.outputSize = 2.5f * fwR;
        }
    }

    // 過給機
    if (s.induction.type != InductionType::Natural && !radial && s.cranks.size() == 1) {
        float topY = 0;
        for (const auto& p : s.pistons) topY = std::max(topY, p.sMax * std::cos(p.axisDeg * kDeg));
        float xm = sc.crankCenter.x;
        if (s.induction.type == InductionType::Turbo) {
            Vec3 c{xm, topY * 0.6f, 1.1f * (topY + 0.4f * b)};
            float tr = 0.45f * b;
            MeshData hs = meshgen::cylinderX(tr * 1.35f, -0.9f * tr, -0.1f * tr, 32);
            hs.append(meshgen::cylinderX(tr * 1.35f, 0.1f * tr, 0.9f * tr, 32), Mat4::identity());
            int hm = sc.addMesh(hs);
            int n = addNode(sc, hm, mat::blued(), Bind::Static, 0, Mat4::translate(c));
            sc.nodes[n].housing = true;
            sc.nodes[n].sectionCut = true;
            sc.nodes[n].heat = HeatSrc::Exhaust;
            MeshData wheels = bladedDisc(0.2f * tr, tr, s.induction.compressorBlades, -0.8f * tr, -0.2f * tr, 30);
            wheels.append(bladedDisc(0.2f * tr, 0.9f * tr, 9, 0.2f * tr, 0.8f * tr, -30), Mat4::identity());
            wheels.append(meshgen::cylinderX(0.08f * tr, -0.9f * tr, 0.9f * tr, 12), Mat4::identity());
            int wm = sc.addMesh(wheels);
            addSpinner(sc, wm, mat::nickel(), c, {1, 0, 0}, 1.0f, 0, 0, 3);
        } else {
            Vec3 c{xm, topY + 0.75f * b, 0};
            float len = 0.8f * (globalXMax - globalXMin);
            int hm = sc.addMesh(meshgen::box({0, 0, 0}, {len, 0.8f * b, 1.3f * b}));
            int n = addNode(sc, hm, mat::paint({0.1f, 0.1f, 0.12f}), Bind::Static, 0, Mat4::translate(c));
            sc.nodes[n].housing = true;
            sc.nodes[n].sectionCut = true;
            int lobes = std::max(2, s.induction.rootsLobes);
            std::vector<Vec2> prof;
            for (int i = 0; i < 72; ++i) {
                float a = kTwoPi * i / 72;
                float rr = 0.2f * b * (0.75f + 0.25f * std::cos(lobes * a));
                prof.push_back({rr * std::cos(a), rr * std::sin(a)});
            }
            int rm = sc.addMesh(meshgen::extrudeStarX(prof, -0.45f * len, 0.45f * len));
            addSpinner(sc, rm, mat::aluminium(), c + Vec3{0, 0, -0.26f * b}, {1, 0, 0}, s.induction.rootsDriveRatio, 0, 0, -1);
            addSpinner(sc, rm, mat::aluminium(), c + Vec3{0, 0, 0.26f * b}, {1, 0, 0}, -s.induction.rootsDriveRatio, kPi / lobes, 0, -1);
        }
    }
}

void buildWankel(const EngineSpec& s, Scene& sc) {
    const WankelDef& w = s.wankel;
    const float R = w.R, e = w.e, W = w.width;
    const int N = 180;
    std::vector<Vec2> housingIn, housingOut, rotorOut;
    for (int i = 0; i < N; ++i) {
        float t = kTwoPi * i / N;
        Vec2 p{(R + 0.002f) * std::cos(t) + e * std::cos(3 * t), (R + 0.002f) * std::sin(t) + e * std::sin(3 * t)};
        housingIn.push_back(p);
        housingOut.push_back({p.x * 1.28f + 0.01f * std::cos(t), p.y * 1.28f + 0.01f * std::sin(t)});
    }
    // ローター輪郭: 3 頂点を結ぶ膨らみ付きフランク
    const float apexR = R * 0.985f, bulge = 0.14f * R;
    for (int k = 0; k < 3; ++k) {
        float a0 = kTwoPi * k / 3, a1 = kTwoPi * (k + 1) / 3;
        Vec2 A{apexR * std::cos(a0), apexR * std::sin(a0)}, B{apexR * std::cos(a1), apexR * std::sin(a1)};
        float am = 0.5f * (a0 + a1);
        Vec2 nrm{std::cos(am), std::sin(am)};
        for (int i = 0; i < N / 3; ++i) {
            float u = static_cast<float>(i) / (N / 3);
            float bump = bulge * 4 * u * (1 - u);
            rotorOut.push_back({A.x + (B.x - A.x) * u + nrm.x * bump, A.y + (B.y - A.y) * u + nrm.y * bump});
        }
    }
    float gearR = 3.0f * e;  // ローター内歯車 (固定歯車 2e と噛合)
    for (int r = 0; r < w.rotors; ++r) {
        float x = r * (W * 1.6f);
        float x0 = x - 0.5f * W, x1 = x + 0.5f * W;
        int hm = sc.addMesh(meshgen::extrudeRingX(housingIn, housingOut, x0, x1));
        int n = addNode(sc, hm, mat::castAluminium(), Bind::Static);
        sc.nodes[n].housing = true;
        sc.nodes[n].sectionCut = true;
        sc.nodes[n].heat = HeatSrc::Liner;
        sc.nodes[n].heatIndex = r * 3;
        // サイドハウジング (シャフト穴付き)
        auto plateHole = meshgen::circleProfile(2.6f * e, N);
        for (int sd = 0; sd < 2; ++sd) {
            float px0 = sd ? x1 : x0 - 0.3f * W, px1 = sd ? x1 + 0.3f * W : x0;
            if (r > 0 && sd == 0) continue;  // 中間プレートは前のローターと共有
            int pm = sc.addMesh(meshgen::extrudeRingX(plateHole, housingOut, px0, px1));
            n = addNode(sc, pm, mat::castIron(), Bind::Static);
            sc.nodes[n].housing = true;
            sc.nodes[n].sectionCut = true;
        }
        // 点火プラグ (短軸側)
        int sp = sc.addMesh(meshgen::cylinderY(0.012f, -(R - e) * 1.4f, -(R - e) * 1.02f, 12));
        n = addNode(sc, sp, mat::nickel(), Bind::Static, 0, Mat4::translate({x, 0, 0}) * rotX(kPi * 0.5f));
        // ローター (内歯車穴付き) + 内歯車
        MeshData rot = meshgen::extrudeRingX(meshgen::circleProfile(gearR * 1.12f, static_cast<int>(rotorOut.size())), rotorOut,
                                             x0 + 0.004f, x1 - 0.004f);
        auto gIn = meshgen::gearProfile(gearR, 30, 0.12f * e * 3, 4);
        rot.append(meshgen::extrudeRingX(gIn, meshgen::circleProfile(gearR * 1.12f, static_cast<int>(gIn.size())), x0 + 0.01f, x0 + 0.3f * W),
                   Mat4::identity());
        int rm = sc.addMesh(rot);
        n = addNode(sc, rm, mat::castIron(), Bind::Rotor, r);
        sc.nodes[n].sectionCut = true;
        sc.nodes[n].heat = HeatSrc::Rotor;
        sc.nodes[n].heatIndex = r;
        // 固定歯車
        int fg = sc.addMesh(meshgen::extrudeRingX(meshgen::circleProfile(1.35f * e, 80), meshgen::gearProfile(2.0f * e, 20, 0.36f * e, 4),
                                                  x0 - 0.02f, x0 + 0.3f * W));
        addNode(sc, fg, mat::steel(), Bind::Static);
    }
    // 偏心軸
    float xs = -0.8f * W, xe = (w.rotors - 1) * W * 1.6f + 0.8f * W;
    MeshData shaft = meshgen::cylinderX(1.3f * e, xs - 0.3f * W, xe + 0.1f * W, 24);
    for (int r = 0; r < w.rotors; ++r) {
        float x = r * (W * 1.6f);
        float ph = (r < static_cast<int>(w.rotorPhaseDeg.size()) ? w.rotorPhaseDeg[r] : 0.0f) * kDeg;
        shaft.append(meshgen::cylinderX(2.5f * e, x - 0.45f * W, x + 0.45f * W, 32), Mat4::translate({0, e * std::cos(ph), e * std::sin(ph)}));
    }
    int sm = sc.addMesh(shaft);
    int n = addNode(sc, sm, mat::forgedSteel(), Bind::Crank, 0);
    sc.nodes[n].stress = StressSrc::Crank;
    sc.crankCenter = {0.5f * (xs + xe), 0, 0};
    sc.cyl1Center = {0, 0, 0};
    sc.cyl1Axis = {0, 0, 1};
    sc.cyl1Size = 3.0f * R;
    sc.sectionX = 0;
    float scale = 1.1f * R;
    addFlywheel(s, sc, xe + 0.15f * W, 1.1f * R, 0.3f * W, 0);
    addGearboxAndOutput(s, sc, xe + 0.15f * W + 0.3f * W + 0.2f * scale, 0, 0, scale, s.drivetrain.gears.size() > 1);
}

void buildTurbine(const EngineSpec& s, Scene& sc) {
    const TurbineDef& t = s.turbine;
    const float L = t.length, R = t.radius;
    const bool fan = t.kind == TurbineKind::Turbofan;
    const float coreR = fan ? 0.45f * R : 0.9f * R;
    // ナセル/ケーシング
    int nm = sc.addMesh(meshgen::tubeX(R * 0.96f, R, 0.0f, fan ? 0.55f * L : L, 64));
    int n = addNode(sc, nm, mat::paint({0.75f, 0.76f, 0.78f}), Bind::Static);
    sc.nodes[n].housing = true;
    sc.nodes[n].sectionCut = true;
    if (fan) {
        int cm = sc.addMesh(meshgen::tubeX(coreR * 0.97f, coreR, 0.18f * L, 0.95f * L, 48));
        n = addNode(sc, cm, mat::castAluminium(), Bind::Static);
        sc.nodes[n].housing = true;
        sc.nodes[n].sectionCut = true;
        // ファン (N1)
        int fm = sc.addMesh(bladedDisc(0.28f * R, 0.94f * R, t.fanBlades, 0.05f * L, 0.12f * L, 32, 0.9f));
        n = addSpinner(sc, fm, mat::titanium(), {0, 0, 0}, {1, 0, 0}, 1.0f, 0, 0, 0);
        sc.nodes[n].stress = StressSrc::Crank;
    }
    int nStages = std::max(1, t.compressorStages);
    int lpStages = fan ? std::max(1, nStages / 3) : 0;
    float cx0 = 0.18f * L, cx1 = 0.47f * L;
    for (int i = 0; i < nStages; ++i) {
        float u = static_cast<float>(i) / nStages;
        float x0 = cx0 + (cx1 - cx0) * u, x1 = x0 + (cx1 - cx0) / nStages * 0.7f;
        float tip = coreR * (0.92f - 0.3f * u), hub = coreR * (0.45f + 0.25f * u);
        int dm = sc.addMesh(bladedDisc(hub, tip, t.compressorBlades + 2 * i, x0, x1, 38));
        addSpinner(sc, dm, mat::titanium(), {0, 0, 0}, {1, 0, 0}, 1.0f, 0.1f * i, 0, i < lpStages ? 0 : 1);
        // 静翼 (ステータ)
        int sm = sc.addMesh(bladedDisc(hub * 0.98f, tip, t.compressorBlades + 2 * i + 3, x1, x1 + (cx1 - cx0) / nStages * 0.25f, -30, 0.9f));
        n = addNode(sc, sm, mat::steel(), Bind::Static);
    }
    // 燃焼器 (環状)
    int cb = sc.addMesh(meshgen::tubeX(coreR * 0.45f, coreR * 0.78f, 0.49f * L, 0.61f * L, 48));
    n = addNode(sc, cb, mat::nickel(), Bind::Static);
    sc.nodes[n].heat = HeatSrc::Combustor;
    sc.nodes[n].sectionCut = true;
    // タービン: HP (N2) → LP (N1)
    int ts = std::max(1, t.turbineStages);
    for (int i = 0; i < ts; ++i) {
        float x0 = 0.63f * L + i * 0.05f * L, x1 = x0 + 0.03f * L;
        float tip = coreR * (0.7f + 0.2f * i / ts), hub = coreR * 0.45f;
        int dm = sc.addMesh(bladedDisc(hub, tip, 40 + 6 * i, x0, x1, -40));
        n = addSpinner(sc, dm, mat::nickel(), {0, 0, 0}, {1, 0, 0}, 1.0f, 0, 0, (fan || t.kind == TurbineKind::Turboshaft) && i > 0 ? 0 : 1);
        sc.nodes[n].heat = HeatSrc::TurbineHot;
    }
    // 軸 (LP 細軸 / HP 中空軸)
    int lp = sc.addMesh(meshgen::cylinderX(0.06f * coreR, 0.05f * L, 0.9f * L, 16));
    addSpinner(sc, lp, mat::steel(), {0, 0, 0}, {1, 0, 0}, 1.0f, 0, 0, 0);
    int hp = sc.addMesh(meshgen::tubeX(0.09f * coreR, 0.16f * coreR, 0.3f * L, 0.66f * L, 20));
    addSpinner(sc, hp, mat::steel(), {0, 0, 0}, {1, 0, 0}, 1.0f, 0, 0, 1);
    // 排気コーン
    int ec = sc.addMesh(meshgen::cylinderX(0.3f * coreR, 0.85f * L, 1.02f * L, 24));
    n = addNode(sc, ec, mat::nickel(), Bind::Static);
    sc.nodes[n].heat = HeatSrc::TurbineHot;
    sc.crankCenter = {0.5f * L, 0, 0};
    sc.cyl1Center = {0.55f * L, 0, 0};
    sc.cyl1Axis = {0, 1, 0};
    sc.cyl1Size = 0.6f * L;
    sc.sectionX = 0.5f * L;
    sc.outputAnchor = {0.08f * L, 0, 0};
    sc.outputSize = R * 2.5f;
    if (t.kind == TurbineKind::Turboshaft) {
        // 前方出力軸 + 減速ギア
        float gs = 0.5f * R;
        int os = sc.addMesh(meshgen::cylinderX(0.08f * R, -0.4f * L, 0.05f * L, 16));
        addSpinner(sc, os, mat::forgedSteel(), {0, 0, 0}, {1, 0, 0}, 1.0f, 0, 0, 0);
        int g1 = sc.addMesh(meshgen::extrudeStarX(meshgen::gearProfile(0.3f * gs, 16, 0.05f * gs), -0.4f * L, -0.35f * L));
        addSpinner(sc, g1, mat::steel(), {0, 0, 0}, {1, 0, 0}, 1.0f, 0, 0, 0);
        int g2 = sc.addMesh(meshgen::extrudeStarX(meshgen::gearProfile(0.9f * gs, 48, 0.05f * gs), -0.4f * L, -0.35f * L));
        addSpinner(sc, g2, mat::steel(), {0, 0, 1.2f * gs}, {1, 0, 0}, -1.0f / 3.0f, 0, 0, 0);
        sc.outputAnchor = {-0.38f * L, 0, 0.6f * gs};
        sc.outputSize = 3.0f * gs;
    }
}

void buildElectric(const EngineSpec& s, Scene& sc) {
    const MotorDef& m = s.motor;
    const float R = m.radius, L = m.length;
    int hm = sc.addMesh(meshgen::tubeX(R * 1.18f, R * 1.32f, -0.1f * L, 1.1f * L, 64));
    int n = addNode(sc, hm, mat::paint({0.2f, 0.22f, 0.26f}), Bind::Static);
    sc.nodes[n].housing = true;
    sc.nodes[n].sectionCut = true;
    sc.nodes[n].heat = HeatSrc::Coolant;
    for (int sd = 0; sd < 2; ++sd) {
        float x0 = sd ? 1.1f * L : -0.18f * L, x1 = sd ? 1.18f * L : -0.1f * L;
        int em = sc.addMesh(meshgen::tubeX(0.1f * R, 1.32f * R, x0, x1, 64));
        n = addNode(sc, em, mat::castAluminium(), Bind::Static);
        sc.nodes[n].housing = true;
        sc.nodes[n].sectionCut = true;
    }
    // ステータ (スロット付き) + コイルエンド
    std::vector<Vec2> inner;
    int slots = std::max(6, m.statorSlots);
    for (int i = 0; i < slots * 4; ++i) {
        float a = kTwoPi * i / (slots * 4);
        float rr = (i % 4 == 1 || i % 4 == 2) ? 0.86f * R : 0.72f * R;
        inner.push_back({rr * std::cos(a), rr * std::sin(a)});
    }
    int st = sc.addMesh(meshgen::extrudeRingX(inner, meshgen::circleProfile(1.18f * R, static_cast<int>(inner.size())), 0.0f, L));
    n = addNode(sc, st, mat::paint({0.35f, 0.36f, 0.38f}), Bind::Static);
    sc.nodes[n].sectionCut = true;
    sc.nodes[n].housing = true;  // X 線でロータを見せる
    sc.nodes[n].heat = HeatSrc::Winding;
    for (int sd = 0; sd < 2; ++sd) {
        float x0 = sd ? L : -0.12f * L, x1 = sd ? 1.12f * L : 0.0f;
        int cm = sc.addMesh(meshgen::tubeX(0.76f * R, 1.05f * R, x0, x1, 48));
        n = addNode(sc, cm, mat::copper(), Bind::Static);
        sc.nodes[n].sectionCut = true;
        sc.nodes[n].heat = HeatSrc::Winding;
    }
    // ロータ
    MeshData rotor = meshgen::cylinderX(0.62f * R, 0.02f * L, 0.98f * L, 48);
    int rm = sc.addMesh(rotor);
    n = addNode(sc, rm, mat::castIron(), Bind::Crank, 0);
    sc.nodes[n].sectionCut = true;
    sc.nodes[n].heat = HeatSrc::Rotor;
    int poles = 2 * std::max(1, m.polePairs);
    for (int k = 0; k < poles; ++k) {
        float a = kTwoPi * k / poles;
        MeshData mg;
        if (m.kind == MotorKind::PMSM) {
            mg = meshgen::box({0.5f * L, 0.66f * R, 0}, {0.94f * L, 0.08f * R, 0.8f * kTwoPi * 0.66f * R / poles});
        } else {
            // 誘導機: かご形導体バー (極数とは無関係だが見た目用に配置)
            mg = meshgen::box({0.5f * L, 0.6f * R, 0}, {1.02f * L, 0.06f * R, 0.05f * R});
        }
        int mm = sc.addMesh(mg);
        Material mt = m.kind == MotorKind::PMSM ? (k % 2 ? mat::paint({0.7f, 0.1f, 0.1f}) : mat::paint({0.1f, 0.2f, 0.7f})) : mat::aluminium();
        n = addNode(sc, mm, mt, Bind::Crank, 0, rotX(a));
        sc.nodes[n].sectionCut = true;
    }
    int sh = sc.addMesh(meshgen::cylinderX(0.12f * R, -0.35f * L, 1.35f * L, 20));
    addNode(sc, sh, mat::forgedSteel(), Bind::Crank, 0);
    sc.crankCenter = {0.5f * L, 0, 0};
    sc.cyl1Center = {0.5f * L, 0, 0};
    sc.cyl1Axis = {0, 1, 0};
    sc.cyl1Size = 2.8f * R;
    sc.sectionX = 0.5f * L;
    addGearboxAndOutput(s, sc, 1.3f * L, 0, 0, 0.9f * R, false);
}

}  // namespace

void buildScene(const EngineSpec& spec, Scene& sc) {
    sc = Scene();
    switch (spec.family) {
        case Family::Reciprocating: buildReciprocating(spec, sc); break;
        case Family::Wankel: buildWankel(spec, sc); break;
        case Family::Turbine: buildTurbine(spec, sc); break;
        case Family::Electric: buildElectric(spec, sc); break;
    }
    // 境界球 (エンジン本体のみ)
    Vec3 mn{1e9f, 1e9f, 1e9f}, mx{-1e9f, -1e9f, -1e9f};
    for (const SceneNode& n : sc.nodes) {
        if (!n.engineBody || n.gas) continue;
        Vec3 a, b;
        sc.meshes[n.mesh].bounds(a, b);
        Mat4 base = n.local;
        if (n.bind == Bind::Crank || n.bind == Bind::RotAxis) base = Mat4::translate(n.pivot) * n.local;
        for (int i = 0; i < 8; ++i) {
            Vec3 c{(i & 1) ? b.x : a.x, (i & 2) ? b.y : a.y, (i & 4) ? b.z : a.z};
            Vec3 w = base.transformPoint(c);
            mn = {std::min(mn.x, w.x), std::min(mn.y, w.y), std::min(mn.z, w.z)};
            mx = {std::max(mx.x, w.x), std::max(mx.y, w.y), std::max(mx.z, w.z)};
        }
    }
    if (mn.x > mx.x) { mn = {-0.5f, -0.5f, -0.5f}; mx = {0.5f, 0.5f, 0.5f}; }
    sc.center = (mn + mx) * 0.5f;
    sc.radius = std::max(0.05f, length(mx - mn) * 0.5f);
    sc.yzRadius = std::max(0.05f, 0.5f * std::max(mx.y - mn.y, mx.z - mn.z));
    if (sc.outputSize <= 0) sc.outputSize = sc.radius;
}

void evaluateScene(const Scene& sc, const EngineSpec& s, const KinState& ks, const VisualAngles& va, std::vector<Mat4>& world) {
    world.resize(sc.nodes.size());
    for (size_t i = 0; i < sc.nodes.size(); ++i) {
        const SceneNode& n = sc.nodes[i];
        switch (n.bind) {
            case Bind::Static: world[i] = n.local; break;
            case Bind::Crank: {
                // 電動機はクランクを持たないので映像スプール 5 (ロータ角) を使う
                float a = n.index < static_cast<int>(ks.crankAngleRad.size()) ? ks.crankAngleRad[n.index] : va.spool[5];
                if (s.family == Family::Wankel) a = ks.eccentricAngleRad;
                world[i] = Mat4::translate(n.pivot) * rotX(a) * n.local;
                break;
            }
            case Bind::Piston: {
                const PistonPose& p = ks.pistons[n.index];
                world[i] = Mat4::translate(p.wrist) * rotX(s.pistons[n.index].axisDeg * kDeg) * n.local;
                break;
            }
            case Bind::Rod: {
                const PistonPose& p = ks.pistons[n.index];
                float ang = std::atan2(p.rodDir.z, p.rodDir.y);
                world[i] = Mat4::translate(p.bigEnd) * rotX(ang) * n.local;
                break;
            }
            case Bind::Valve: {
                float lift = n.sub == 0 ? ks.liftIn[n.index] : ks.liftEx[n.index];
                world[i] = Mat4::translate(n.dir * (-lift)) * n.local;
                break;
            }
            case Bind::RotAxis: {
                float src = n.spool >= 0 ? va.spool[n.spool]
                                         : (n.index < static_cast<int>(ks.crankAngleRad.size()) ? ks.crankAngleRad[n.index] : va.spool[5]);
                float a = n.ratio * src + n.phase;
                world[i] = Mat4::translate(n.pivot) * Mat4::rotation(Quat::axisAngle(n.axis, a)) * n.local;
                break;
            }
            case Bind::Rotor:
                world[i] = Mat4::translate(ks.rotorCenter[n.index]) * rotX(ks.rotorAngleRad[n.index]) *
                           Mat4::translate({-ks.rotorCenter[n.index].x, 0, 0}) * n.local;
                break;
            case Bind::SlideValve: world[i] = Mat4::translate(n.dir * ks.slideValve[n.index]) * n.local; break;
            case Bind::OutputShaft:
                if (n.index == 0) {
                    world[i] = n.local * rotX(va.outputShaft);
                } else {
                    world[i] = Mat4::translate(n.pivot) * Mat4::rotation(Quat::axisAngle(n.axis, n.ratio * va.outputShaft)) * n.local;
                }
                break;
            case Bind::GasVolume: {
                // ピストン冠面から天井 (または対向ピストン冠面) までの円柱
                const ChamberDef& ch = s.chambers[n.index];
                const PistonPose& p = ks.pistons[ch.pistons[0]];
                Vec3 a = p.axis;
                Vec3 from, to;
                if (n.sub == 1 && ch.pistons.size() == 2) {
                    const PistonPose& q = ks.pistons[ch.pistons[1]];
                    from = p.wrist + a * n.ratio;
                    to = q.wrist + q.axis * n.ratio;
                } else if (n.sub == 3) {
                    from = n.pivot;
                    to = p.wrist + a * n.ratio;
                } else {
                    from = p.wrist + a * n.ratio;
                    to = n.pivot;
                }
                float len = std::max(1e-4f, dot(to - from, a));
                world[i] = Mat4::translate(from) * rotX(s.pistons[ch.pistons[0]].axisDeg * kDeg) * Mat4::scale({1, len, 1});
                break;
            }
        }
    }
}

}  // namespace es
