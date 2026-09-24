#include "EngineSpec.h"

#include <algorithm>
#include <cmath>

#include "../sim/EngineKinematics.h"
#include "MathUtil.h"

namespace es {

const char* familyName(Family f) {
    switch (f) {
        case Family::Reciprocating: return "reciprocating";
        case Family::Wankel: return "wankel";
        case Family::Turbine: return "turbine";
        case Family::Electric: return "electric";
    }
    return "?";
}

const char* cycleName(Cycle c) {
    switch (c) {
        case Cycle::Otto4: return "otto4";
        case Cycle::Otto2: return "otto2";
        case Cycle::Diesel4: return "diesel4";
        case Cycle::Diesel2: return "diesel2";
        case Cycle::Steam: return "steam";
        case Cycle::Stirling: return "stirling";
        case Cycle::Wankel4: return "wankel4";
        case Cycle::None: return "none";
    }
    return "?";
}

namespace {

float f(const JsonValue& v, const char* key, float def) { return static_cast<float>(v.num(key, def)); }

std::vector<float> fArr(const JsonValue& v, const char* key) {
    std::vector<float> out;
    for (double d : v.numArray(key)) out.push_back(static_cast<float>(d));
    return out;
}

Cycle parseCycle(const std::string& s) {
    if (s == "otto4") return Cycle::Otto4;
    if (s == "otto2") return Cycle::Otto2;
    if (s == "diesel4") return Cycle::Diesel4;
    if (s == "diesel2") return Cycle::Diesel2;
    if (s == "steam") return Cycle::Steam;
    if (s == "stirling") return Cycle::Stirling;
    if (s == "wankel4") return Cycle::Wankel4;
    return Cycle::None;
}

// 1 本のクランクを持つ標準的な構成の共通初期化
void singleCrank(EngineSpec& s) {
    s.cranks.clear();
    CrankDef c;
    c.radius = s.stroke * 0.5f;
    s.cranks.push_back(c);
}

int addPiston(EngineSpec& s, int throwIdx, float axisDeg, float xOffset, int bank) {
    PistonDef p;
    p.throwIdx = throwIdx;
    p.axisDeg = axisDeg;
    p.xOffset = xOffset;
    p.rodLength = s.rodLength;
    p.bore = s.bore;
    p.bank = bank;
    s.pistons.push_back(p);
    return static_cast<int>(s.pistons.size()) - 1;
}

void addCombustionChamber(EngineSpec& s, std::vector<int> pistons, int number, int bank) {
    ChamberDef c;
    c.kind = ChamberKind::Combustion;
    c.pistons = std::move(pistons);
    c.signs.assign(c.pistons.size(), 1.0f);
    c.area = kPi * 0.25f * s.bore * s.bore;
    c.number = number;
    c.bank = bank;
    s.chambers.push_back(c);
}

int addBank(EngineSpec& s, float axisDeg, int crank = 0) {
    BankDef b;
    b.axisDeg = axisDeg;
    b.crank = crank;
    s.banks.push_back(b);
    return static_cast<int>(s.banks.size()) - 1;
}

float throwSpacing(const EngineSpec& s, float factor) { return s.bore * factor; }

bool buildInline(EngineSpec& s, const JsonValue& L, std::string& err) {
    int n = static_cast<int>(L.num("count", 4));
    std::vector<float> pins = fArr(L, "throwAngles");
    if (static_cast<int>(pins.size()) != n) {
        err = "inline: throwAngles must have count entries";
        return false;
    }
    float tilt = f(L, "tilt", 0);
    singleCrank(s);
    int bank = addBank(s, tilt);
    float sp = throwSpacing(s, f(L, "spacing", 1.18f));
    for (int i = 0; i < n; ++i) {
        s.throws.push_back({0, i * sp, pins[i]});
        int p = addPiston(s, i, tilt, 0, bank);
        s.banks[bank].pistons.push_back(p);
        addCombustionChamber(s, {p}, i + 1, bank);
    }
    return true;
}

bool buildV(EngineSpec& s, const JsonValue& L, std::string& err) {
    int n = static_cast<int>(L.num("count", 8));
    if (n % 2) { err = "v: count must be even"; return false; }
    int half = n / 2;
    float bankAngle = f(L, "bankAngle", 90);
    std::vector<float> pins = fArr(L, "throwAngles");
    if (static_cast<int>(pins.size()) != half) { err = "v: throwAngles must have count/2 entries"; return false; }
    // splitPin: 左右バンクでピンをずらすオフセット角 (例: 90°V6 の等間隔点火化)
    float split = f(L, "splitPin", 0);
    bool alternate = L.str("numbering", "bank") == "alternate";
    singleCrank(s);
    int bA = addBank(s, -bankAngle * 0.5f);
    int bB = addBank(s, bankAngle * 0.5f);
    float sp = throwSpacing(s, f(L, "spacing", 1.25f));
    float off = s.bore * 0.27f;
    for (int i = 0; i < half; ++i) {
        int tA, tB;
        if (split != 0) {
            s.throws.push_back({0, i * sp - off, pins[i]});
            tA = static_cast<int>(s.throws.size()) - 1;
            s.throws.push_back({0, i * sp + off, pins[i] + split});
            tB = static_cast<int>(s.throws.size()) - 1;
        } else {
            s.throws.push_back({0, i * sp, pins[i]});
            tA = tB = static_cast<int>(s.throws.size()) - 1;
        }
        float oA = split != 0 ? 0 : -off, oB = split != 0 ? 0 : off;
        int pA = addPiston(s, tA, s.banks[bA].axisDeg, oA, bA);
        int pB = addPiston(s, tB, s.banks[bB].axisDeg, oB, bB);
        s.banks[bA].pistons.push_back(pA);
        s.banks[bB].pistons.push_back(pB);
        int numA = alternate ? 2 * i + 1 : i + 1;
        int numB = alternate ? 2 * i + 2 : half + i + 1;
        addCombustionChamber(s, {pA}, numA, bA);
        addCombustionChamber(s, {pB}, numB, bB);
    }
    return true;
}

bool buildFlat(EngineSpec& s, const JsonValue& L, std::string& err) {
    int n = static_cast<int>(L.num("count", 4));
    std::vector<float> pins = fArr(L, "throwAngles");
    if (static_cast<int>(pins.size()) != n) { err = "flat: throwAngles must have count entries"; return false; }
    // sides: 各気筒が右(+1)/左(-1) どちらのバンクか
    std::vector<float> sides = fArr(L, "sides");
    singleCrank(s);
    int bR = addBank(s, 90), bLft = addBank(s, -90);
    float sp = throwSpacing(s, f(L, "spacing", 0.62f));
    for (int i = 0; i < n; ++i) {
        float side = i < static_cast<int>(sides.size()) ? sides[i] : ((i % 2) ? -1.0f : 1.0f);
        int b = side > 0 ? bR : bLft;
        s.throws.push_back({0, i * sp, pins[i]});
        int p = addPiston(s, i, side > 0 ? 90.0f : -90.0f, 0, b);
        s.banks[b].pistons.push_back(p);
        addCombustionChamber(s, {p}, i + 1, b);
    }
    return true;
}

bool buildRadial(EngineSpec& s, const JsonValue& L, std::string&) {
    int perRow = static_cast<int>(L.num("perRow", 9));
    int rows = static_cast<int>(L.num("rows", 1));
    std::vector<float> rowThrows = fArr(L, "rowThrowAngles");
    float stagger = f(L, "rowStagger", 180.0f / perRow);
    singleCrank(s);
    float r = s.stroke * 0.5f;
    float rk = f(L, "knuckleRadius", 1.0f * r);
    float rowSp = s.bore * f(L, "rowSpacing", 1.6f);
    int number = 1;
    for (int row = 0; row < rows; ++row) {
        float pin = row < static_cast<int>(rowThrows.size()) ? rowThrows[row] : (row % 2 ? 180.0f : 0.0f);
        s.throws.push_back({0, row * rowSp, pin});
        int t = static_cast<int>(s.throws.size()) - 1;
        int master = -1;
        float masterAxis = row * stagger;
        for (int j = 0; j < perRow; ++j) {
            float axis = masterAxis + j * 360.0f / perRow;
            int b = addBank(s, axis);
            int p = addPiston(s, t, axis, 0, b);
            PistonDef& pd = s.pistons[p];
            if (j == 0) {
                pd.rod = RodType::Master;
                master = p;
            } else {
                pd.rod = RodType::Articulated;
                pd.master = master;
                pd.knuckleRadius = rk;
                pd.knuckleAngleDeg = j * 360.0f / perRow;
                pd.rodLength = s.rodLength - rk;
            }
            s.banks[b].pistons.push_back(p);
            addCombustionChamber(s, {p}, number++, b);
        }
    }
    return true;
}

// ユンカース Jumo 205 型: 上下 2 本のクランク, 各シリンダに対向 2 ピストン
bool buildOpposed(EngineSpec& s, const JsonValue& L, std::string&) {
    int n = static_cast<int>(L.num("count", 6));
    std::vector<float> pins = fArr(L, "throwAngles");
    float lead = f(L, "exhaustLead", 12);
    float r = s.stroke * 0.5f;
    float gap = s.bore * 0.35f;
    float d = r + s.rodLength + gap * 0.5f;
    s.cranks.clear();
    s.cranks.push_back({d, 0, 1, lead, r});    // 上クランク (排気ピストン)
    s.cranks.push_back({-d, 0, 1, 0, r});      // 下クランク (吸気ピストン)
    int bank = addBank(s, 0);
    float sp = throwSpacing(s, 1.25f);
    for (int i = 0; i < n; ++i) {
        float pin = i < static_cast<int>(pins.size()) ? pins[i] : i * 360.0f / n;
        s.throws.push_back({0, i * sp, pin + 180.0f});
        s.throws.push_back({1, i * sp, pin});
        int up = addPiston(s, 2 * i, 180, 0, bank);
        int dn = addPiston(s, 2 * i + 1, 0, 0, bank);
        s.banks[bank].pistons.push_back(up);
        s.banks[bank].pistons.push_back(dn);
        addCombustionChamber(s, {up, dn}, i + 1, bank);
    }
    return true;
}

// ネイピア・デルティック: 3 本のクランクを三角形に配置, 1 本が逆回転
bool buildDeltic(EngineSpec& s, const JsonValue& L, std::string&) {
    int perBank = static_cast<int>(L.num("perBank", 6));
    float lead = f(L, "exhaustLead", 20);
    float r = s.stroke * 0.5f;
    float side = 2.0f * (r + s.rodLength) + s.bore * 0.35f;
    float circ = side / std::sqrt(3.0f);
    s.cranks.clear();
    // 頂点 (上向き三角形を反転した "Δ" 配置)
    for (int k = 0; k < 3; ++k) {
        float a = deg2rad(180.0f + k * 120.0f);
        CrankDef c;
        c.y = circ * std::cos(a);
        c.z = circ * std::sin(a);
        c.radius = r;
        c.dir = (k == 1) ? -1.0f : 1.0f;  // 実機同様 1 本だけ逆回転
        s.cranks.push_back(c);
    }
    float sp = throwSpacing(s, 1.3f);
    int number = 1;
    for (int b = 0; b < 3; ++b) {
        int ca = b, cb = (b + 1) % 3;
        float dy = s.cranks[cb].y - s.cranks[ca].y, dz = s.cranks[cb].z - s.cranks[ca].z;
        float axisAB = rad2deg(std::atan2(dz, dy));
        int bank = addBank(s, axisAB, ca);
        for (int i = 0; i < perBank; ++i) {
            // 内死点に来る主クランク角 theta0 を全気筒で等間隔に配る
            float theta0 = (i * 3 + b) * 360.0f / (3.0f * perBank);
            float x = i * sp;
            s.throws.push_back({ca, x, axisAB - s.cranks[ca].dir * theta0 + lead});
            int ta = static_cast<int>(s.throws.size()) - 1;
            s.throws.push_back({cb, x, axisAB + 180.0f - s.cranks[cb].dir * theta0});
            int tb = static_cast<int>(s.throws.size()) - 1;
            int pa = addPiston(s, ta, axisAB, 0, bank);
            int pb = addPiston(s, tb, axisAB + 180.0f, 0, bank);
            s.banks[bank].pistons.push_back(pa);
            s.banks[bank].pistons.push_back(pb);
            addCombustionChamber(s, {pa, pb}, number++, bank);
        }
    }
    return true;
}

// 横置き蒸気機関 (クロスヘッド, 単動/複動)
bool buildSteam(EngineSpec& s, const JsonValue& L, std::string&) {
    int n = static_cast<int>(L.num("count", 2));
    bool doubleActing = L.boolean("doubleActing", true);
    std::vector<float> pins = fArr(L, "throwAngles");
    singleCrank(s);
    int bank = addBank(s, 90);
    float sp = throwSpacing(s, 1.5f);
    float area = kPi * 0.25f * s.bore * s.bore;
    float rodArea = kPi * 0.25f * (s.bore * 0.15f) * (s.bore * 0.15f);
    int number = 1;
    for (int i = 0; i < n; ++i) {
        float pin = i < static_cast<int>(pins.size()) ? pins[i] : i * 90.0f;
        s.throws.push_back({0, i * sp, pin});
        int p = addPiston(s, i, 90, 0, bank);
        s.pistons[p].crosshead = true;
        s.banks[bank].pistons.push_back(p);
        ChamberDef head;
        head.kind = ChamberKind::SteamHead;
        head.pistons = {p};
        head.signs = {1.0f};
        head.area = area;
        head.number = number;
        s.chambers.push_back(head);
        if (doubleActing) {
            ChamberDef crank = head;
            crank.kind = ChamberKind::SteamCrank;
            crank.signs = {-1.0f};
            crank.area = area - rodArea;
            s.chambers.push_back(crank);
        }
        ++number;
    }
    s.cylinders = n;
    return true;
}

// α 型スターリング: 高温/低温シリンダを 90° V 配置, 同一クランクピン
bool buildStirling(EngineSpec& s, const JsonValue& L, std::string&) {
    float vAngle = f(L, "vAngle", 90);
    singleCrank(s);
    int bh = addBank(s, -vAngle * 0.5f), bc = addBank(s, vAngle * 0.5f);
    s.throws.push_back({0, 0, 0});
    float off = s.bore * 0.3f;
    int ph = addPiston(s, 0, s.banks[bh].axisDeg, -off, bh);
    int pc = addPiston(s, 0, s.banks[bc].axisDeg, off, bc);
    s.banks[bh].pistons.push_back(ph);
    s.banks[bc].pistons.push_back(pc);
    ChamberDef hot;
    hot.kind = ChamberKind::StirlingHot;
    hot.pistons = {ph};
    hot.signs = {1.0f};
    hot.area = kPi * 0.25f * s.bore * s.bore;
    hot.number = 1;
    hot.bank = bh;
    s.chambers.push_back(hot);
    ChamberDef cold = hot;
    cold.kind = ChamberKind::StirlingCold;
    cold.pistons = {pc};
    cold.number = 2;
    cold.bank = bc;
    s.chambers.push_back(cold);
    s.cylinders = 2;
    return true;
}

bool buildWankel(EngineSpec& s, const JsonValue& W, std::string&) {
    WankelDef& w = s.wankel;
    w.rotors = static_cast<int>(W.num("rotors", 2));
    w.e = f(W, "eccentricity", 0.015f);
    w.R = f(W, "generatingRadius", 0.105f);
    w.width = f(W, "width", 0.080f);
    w.rotorPhaseDeg = fArr(W, "rotorPhases");
    while (static_cast<int>(w.rotorPhaseDeg.size()) < w.rotors)
        w.rotorPhaseDeg.push_back(w.rotorPhaseDeg.size() * 360.0f / w.rotors);
    singleCrank(s);
    s.cranks[0].radius = w.e;
    s.stroke = 2 * w.e;
    int number = 1;
    for (int r = 0; r < w.rotors; ++r) {
        for (int k = 0; k < 3; ++k) {
            ChamberDef c;
            c.kind = ChamberKind::Combustion;
            c.rotor = r;
            c.number = number++;
            // 面 k の中点方向 = phi/3 + 120k + 60 が短軸 (+90°) に来る偏心軸角
            c.wankelPhaseDeg = static_cast<float>(wrapPos(90.0 - 360.0 * k - w.rotorPhaseDeg[r], 1080.0));
            s.chambers.push_back(c);
        }
    }
    s.cylinders = w.rotors;
    return true;
}

}  // namespace

bool loadEngineSpec(const JsonValue& J, EngineSpec& s, std::string& err) {
    s = EngineSpec();
    s.id = J.str("id", "unnamed");
    s.name = J.str("name", s.id);
    s.category = J.str("category", "");
    s.description = J.str("description", "");
    std::string fam = J.str("family", "reciprocating");
    if (fam == "reciprocating") s.family = Family::Reciprocating;
    else if (fam == "wankel") s.family = Family::Wankel;
    else if (fam == "turbine") s.family = Family::Turbine;
    else if (fam == "electric") s.family = Family::Electric;
    else { err = "unknown family: " + fam; return false; }

    s.cycle = parseCycle(J.str("cycle", s.family == Family::Wankel ? "wankel4" : (s.family == Family::Reciprocating ? "otto4" : "none")));
    s.idleRpm = f(J, "idleRpm", 800);
    s.redlineRpm = f(J, "redlineRpm", 7000);
    s.limiterRpm = f(J, "limiterRpm", s.redlineRpm * 1.03f);
    s.maxRpm = f(J, "maxRpm", s.limiterRpm * 1.1f);
    s.bore = f(J, "bore", 0.086f);
    s.stroke = f(J, "stroke", 0.086f);
    s.rodLength = f(J, "rodLength", s.stroke * 1.65f);
    s.compressionRatio = f(J, "compressionRatio", s.isDiesel() ? 17.0f : 10.5f);
    s.inertia = f(J, "inertia", 0.2f);
    s.reciprocatingMass = f(J, "reciprocatingMass", 0.4f);
    s.sparkAdvanceDeg = f(J, "sparkAdvance", s.isDiesel() ? 8.0f : 22.0f);
    s.burnDurationDeg = f(J, "burnDuration", s.isDiesel() ? 45.0f : 55.0f);
    s.boilerPressure = f(J, "boilerPressure", 1.2e6f);
    s.cutoff = f(J, "cutoff", 0.35f);
    s.stirlingHotK = f(J, "hotTemp", 900);
    s.stirlingColdK = f(J, "coldTemp", 330);
    s.stirlingMeanPressure = f(J, "meanPressure", 3e6f);
    for (double d : J.numArray("firingOrder")) s.firingOrder.push_back(static_cast<int>(d));

    const JsonValue& V = J["valvetrain"];
    if (V.isObject()) {
        s.valves.intakePerCyl = static_cast<int>(V.num("intakeValves", 2));
        s.valves.exhaustPerCyl = static_cast<int>(V.num("exhaustValves", 2));
        s.valves.ivoDeg = static_cast<float>(wrapPos(360.0 - V.num("ivoBTDC", 10), 720.0));
        s.valves.ivcDeg = static_cast<float>(540.0 + V.num("ivcABDC", 50));
        s.valves.evoDeg = static_cast<float>(180.0 - V.num("evoBBDC", 50));
        s.valves.evcDeg = static_cast<float>(360.0 + V.num("evcATDC", 10));
        s.valves.liftIn = f(V, "liftIn", s.bore * 0.12f);
        s.valves.liftEx = f(V, "liftEx", s.bore * 0.11f);
        s.valves.overhead = V.str("type", "DOHC") != "OHV";
        s.valves.exhaustPortDeg = f(V, "exhaustPortATDC", 95);
        s.valves.transferPortDeg = f(V, "transferPortATDC", 120);
    } else {
        s.valves.liftIn = s.bore * 0.12f;
        s.valves.liftEx = s.bore * 0.11f;
    }

    const JsonValue& I = J["induction"];
    if (I.isObject()) {
        std::string t = I.str("type", "natural");
        s.induction.type = t == "turbo" ? InductionType::Turbo : (t == "roots" ? InductionType::Roots : InductionType::Natural);
        s.induction.maxBoostBar = f(I, "maxBoostBar", 1.0f);
        s.induction.compressorBlades = static_cast<int>(I.num("compressorBlades", 11));
        s.induction.turboMaxRpm = f(I, "maxRpm", 180000);
        s.induction.spoolTimeConst = f(I, "spoolTime", 0.8f);
        s.induction.rootsDriveRatio = f(I, "driveRatio", 2.0f);
        s.induction.rootsLobes = static_cast<int>(I.num("lobes", 3));
    }

    const JsonValue& E = J["exhaust"];
    if (E.isObject()) {
        s.exhaust.runnerLengths = fArr(E, "runnerLengths");
        s.exhaust.collectorLength = f(E, "collectorLength", 0.8f);
        s.exhaust.tailpipeLength = f(E, "tailpipeLength", 1.5f);
        s.exhaust.mufflerVolume = f(E, "mufflerVolume", 0.02f);
    }

    const JsonValue& D = J["drivetrain"];
    if (D.isObject()) {
        std::vector<float> g = fArr(D, "gears");
        if (!g.empty()) s.drivetrain.gears = g;
        s.drivetrain.finalDrive = f(D, "finalDrive", 3.9f);
        s.drivetrain.propeller = D.str("type", "gearbox") == "propeller";
        s.drivetrain.hasGearbox = D.str("type", "gearbox") == "gearbox";
        s.drivetrain.propReduction = f(D, "reduction", 1.0f);
        s.drivetrain.propBlades = static_cast<int>(D.num("blades", 3));
        s.drivetrain.propDiameter = f(D, "diameter", 3.0f);
    }

    const JsonValue& Veh = J["vehicle"];
    if (Veh.isObject()) {
        s.vehicle.massKg = f(Veh, "massKg", s.vehicle.massKg);
        s.vehicle.wheelRadius = f(Veh, "wheelRadius", s.vehicle.wheelRadius);
        s.vehicle.cdA = f(Veh, "cdA", s.vehicle.cdA);
        s.vehicle.rollingCoeff = f(Veh, "rollingCoeff", s.vehicle.rollingCoeff);
    }
    const JsonValue& Tu = J["tuning"];
    if (Tu.isObject()) {
        s.tuning.frictionScale = f(Tu, "frictionScale", 1.0f);
        s.tuning.backpressureScale = f(Tu, "backpressureScale", 1.0f);
        s.tuning.coolingScale = f(Tu, "coolingScale", 1.0f);
        s.tuning.breathingScale = std::max(0.2f, f(Tu, "breathingScale", 1.0f));
    }

    bool ok = true;
    if (s.family == Family::Reciprocating) {
        const JsonValue& L = J["layout"];
        std::string type = L.str("type", "inline");
        if (type == "inline") ok = buildInline(s, L, err);
        else if (type == "v") ok = buildV(s, L, err);
        else if (type == "flat") ok = buildFlat(s, L, err);
        else if (type == "radial") ok = buildRadial(s, L, err);
        else if (type == "opposed") ok = buildOpposed(s, L, err);
        else if (type == "deltic") ok = buildDeltic(s, L, err);
        else if (type == "steam") { s.cycle = Cycle::Steam; ok = buildSteam(s, L, err); }
        else if (type == "stirling") { s.cycle = Cycle::Stirling; ok = buildStirling(s, L, err); }
        else { err = "unknown layout: " + type; return false; }
    } else if (s.family == Family::Wankel) {
        s.cycle = Cycle::Wankel4;
        ok = buildWankel(s, J["wankel"], err);
    } else if (s.family == Family::Turbine) {
        const JsonValue& T = J["turbine"];
        std::string k = T.str("kind", "turbojet");
        s.turbine.kind = k == "turbofan" ? TurbineKind::Turbofan : (k == "turboshaft" ? TurbineKind::Turboshaft : TurbineKind::Turbojet);
        s.turbine.fanBlades = static_cast<int>(T.num("fanBlades", s.turbine.kind == TurbineKind::Turbofan ? 24 : 0));
        s.turbine.compressorStages = static_cast<int>(T.num("compressorStages", 8));
        s.turbine.turbineStages = static_cast<int>(T.num("turbineStages", 2));
        s.turbine.compressorBlades = static_cast<int>(T.num("compressorBlades", 30));
        s.turbine.n1MaxRpm = f(T, "n1MaxRpm", 10000);
        s.turbine.n2MaxRpm = f(T, "n2MaxRpm", 15000);
        s.turbine.maxThrustN = f(T, "maxThrustN", 50000);
        s.turbine.maxShaftPowerW = f(T, "maxShaftPowerW", 0);
        s.turbine.spoolTimeConst = f(T, "spoolTime", 2.5f);
        s.turbine.length = f(T, "length", 2.5f);
        s.turbine.radius = f(T, "radius", 0.5f);
        s.turbine.outputReduction = f(T, "outputReduction", 1.0f);
        s.cycle = Cycle::None;
    } else {
        const JsonValue& M = J["motor"];
        s.motor.kind = M.str("kind", "pmsm") == "induction" ? MotorKind::Induction : MotorKind::PMSM;
        s.motor.polePairs = static_cast<int>(M.num("polePairs", 4));
        s.motor.statorSlots = static_cast<int>(M.num("statorSlots", 48));
        s.motor.pwmHz = f(M, "pwmHz", 8000);
        s.motor.ratedTorque = f(M, "ratedTorque", 300);
        s.motor.ratedPowerW = f(M, "ratedPowerW", 150000);
        s.motor.maxRpm = f(M, "maxRpm", 16000);
        s.motor.slipRated = f(M, "slipRated", 0.03f);
        s.motor.radius = f(M, "radius", 0.12f);
        s.motor.length = f(M, "length", 0.2f);
        s.cycle = Cycle::None;
    }
    if (!ok) return false;

    if (s.family == Family::Reciprocating || s.family == Family::Wankel) {
        if (s.cranks.empty() || s.chambers.empty()) { err = "layout produced no cylinders"; return false; }
        for (size_t p = 0; p < s.pistons.size(); ++p) {
            const PistonDef& pd = s.pistons[p];
            if (pd.rodLength <= s.cranks[s.throws[pd.throwIdx].crank].radius * 1.2f) {
                err = "rod too short for crank radius";
                return false;
            }
            if (pd.rod == RodType::Articulated && (pd.master < 0 || pd.master >= static_cast<int>(p))) {
                err = "articulated rod must reference an earlier master";
                return false;
            }
        }
        EngineKinematics::prepare(s);
    }
    return true;
}

bool loadEngineSpecFromText(const std::string& json, EngineSpec& out, std::string& error) {
    JsonValue root;
    if (!parseJson(json, root, error)) return false;
    return loadEngineSpec(root, out, error);
}

}  // namespace es
