#include "Json.h"

#include <cstdlib>

namespace es {

const JsonValue& JsonValue::nullValue() {
    static const JsonValue v;
    return v;
}

const JsonValue& JsonValue::operator[](size_t i) const {
    if (type_ != Type::Array || i >= arr_.size()) return nullValue();
    return arr_[i];
}

const JsonValue& JsonValue::operator[](const std::string& key) const {
    if (type_ != Type::Object) return nullValue();
    auto it = obj_.find(key);
    return it == obj_.end() ? nullValue() : it->second;
}

double JsonValue::num(const std::string& key, double def) const { return (*this)[key].asNumber(def); }

std::string JsonValue::str(const std::string& key, const std::string& def) const {
    const JsonValue& v = (*this)[key];
    return v.isString() ? v.str_ : def;
}

bool JsonValue::boolean(const std::string& key, bool def) const { return (*this)[key].asBool(def); }

std::vector<double> JsonValue::numArray(const std::string& key) const {
    std::vector<double> out;
    const JsonValue& v = (*this)[key];
    if (!v.isArray()) return out;
    for (const auto& e : v.arr_) out.push_back(e.asNumber());
    return out;
}

class JsonParser {
public:
    JsonParser(const std::string& t) : s_(t) {}

    bool parse(JsonValue& out, std::string& err) {
        skipWs();
        if (!value(out)) {
            err = error_ + " at offset " + std::to_string(pos_);
            return false;
        }
        skipWs();
        if (pos_ != s_.size()) {
            err = "trailing characters at offset " + std::to_string(pos_);
            return false;
        }
        return true;
    }

private:
    const std::string& s_;
    size_t pos_ = 0;
    std::string error_;
    int depth_ = 0;

    bool fail(const char* msg) {
        error_ = msg;
        return false;
    }
    void skipWs() {
        while (pos_ < s_.size()) {
            char c = s_[pos_];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                ++pos_;
            } else if (c == '/' && pos_ + 1 < s_.size() && s_[pos_ + 1] == '/') {
                // カタログ作者向けに // コメントを許容
                while (pos_ < s_.size() && s_[pos_] != '\n') ++pos_;
            } else {
                break;
            }
        }
    }
    bool literal(const char* lit) {
        size_t n = 0;
        while (lit[n]) ++n;
        if (s_.compare(pos_, n, lit) != 0) return false;
        pos_ += n;
        return true;
    }
    bool value(JsonValue& v) {
        if (pos_ >= s_.size()) return fail("unexpected end");
        if (++depth_ > 64) return fail("nesting too deep");
        bool ok;
        char c = s_[pos_];
        if (c == '{') ok = object(v);
        else if (c == '[') ok = array(v);
        else if (c == '"') { v.type_ = JsonValue::Type::String; ok = string(v.str_); }
        else if (c == 't') { ok = literal("true"); v.type_ = JsonValue::Type::Bool; v.bool_ = true; if (!ok) fail("bad literal"); }
        else if (c == 'f') { ok = literal("false"); v.type_ = JsonValue::Type::Bool; v.bool_ = false; if (!ok) fail("bad literal"); }
        else if (c == 'n') { ok = literal("null"); v.type_ = JsonValue::Type::Null; if (!ok) fail("bad literal"); }
        else ok = number(v);
        --depth_;
        return ok;
    }
    bool number(JsonValue& v) {
        const char* begin = s_.c_str() + pos_;
        char* end = nullptr;
        double d = std::strtod(begin, &end);
        if (end == begin) return fail("invalid value");
        pos_ += static_cast<size_t>(end - begin);
        v.type_ = JsonValue::Type::Number;
        v.num_ = d;
        return true;
    }
    static void appendUtf8(std::string& out, unsigned cp) {
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    bool hex4(unsigned& cp) {
        if (pos_ + 4 > s_.size()) return fail("bad unicode escape");
        cp = 0;
        for (int i = 0; i < 4; ++i) {
            char h = s_[pos_++];
            cp <<= 4;
            if (h >= '0' && h <= '9') cp |= h - '0';
            else if (h >= 'a' && h <= 'f') cp |= h - 'a' + 10;
            else if (h >= 'A' && h <= 'F') cp |= h - 'A' + 10;
            else return fail("bad hex digit");
        }
        return true;
    }
    bool string(std::string& out) {
        ++pos_;  // "
        while (pos_ < s_.size()) {
            char c = s_[pos_++];
            if (c == '"') return true;
            if (c != '\\') { out += c; continue; }
            if (pos_ >= s_.size()) break;
            char e = s_[pos_++];
            switch (e) {
                case '"': out += '"'; break;
                case '\\': out += '\\'; break;
                case '/': out += '/'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    unsigned cp;
                    if (!hex4(cp)) return false;
                    if (cp >= 0xD800 && cp < 0xDC00 && pos_ + 1 < s_.size() && s_[pos_] == '\\' && s_[pos_ + 1] == 'u') {
                        pos_ += 2;
                        unsigned lo;
                        if (!hex4(lo)) return false;
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    }
                    appendUtf8(out, cp);
                    break;
                }
                default: return fail("bad escape");
            }
        }
        return fail("unterminated string");
    }
    bool array(JsonValue& v) {
        v.type_ = JsonValue::Type::Array;
        ++pos_;
        skipWs();
        if (pos_ < s_.size() && s_[pos_] == ']') { ++pos_; return true; }
        while (true) {
            v.arr_.emplace_back();
            skipWs();
            if (!value(v.arr_.back())) return false;
            skipWs();
            if (pos_ >= s_.size()) return fail("unterminated array");
            char c = s_[pos_++];
            if (c == ']') return true;
            if (c != ',') return fail("expected , or ]");
            skipWs();
            if (pos_ < s_.size() && s_[pos_] == ']') { ++pos_; return true; }  // 末尾カンマ許容
        }
    }
    bool object(JsonValue& v) {
        v.type_ = JsonValue::Type::Object;
        ++pos_;
        skipWs();
        if (pos_ < s_.size() && s_[pos_] == '}') { ++pos_; return true; }
        while (true) {
            skipWs();
            if (pos_ >= s_.size() || s_[pos_] != '"') return fail("expected key");
            std::string key;
            if (!string(key)) return false;
            skipWs();
            if (pos_ >= s_.size() || s_[pos_++] != ':') return fail("expected :");
            skipWs();
            JsonValue child;
            if (!value(child)) return false;
            v.obj_[key] = std::move(child);
            skipWs();
            if (pos_ >= s_.size()) return fail("unterminated object");
            char c = s_[pos_++];
            if (c == '}') return true;
            if (c != ',') return fail("expected , or }");
            skipWs();
            if (pos_ < s_.size() && s_[pos_] == '}') { ++pos_; return true; }
        }
    }
};

bool parseJson(const std::string& text, JsonValue& out, std::string& error) {
    out = JsonValue();
    JsonParser p(text);
    return p.parse(out, error);
}

}  // namespace es
