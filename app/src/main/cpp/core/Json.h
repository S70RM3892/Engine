// 例外を使わない最小 JSON DOM パーサ (エンジンカタログ読み込み専用)。
#pragma once

#include <map>
#include <memory>
#include <string>
#include <vector>

namespace es {

class JsonValue {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Type type() const { return type_; }
    bool isNull() const { return type_ == Type::Null; }
    bool isNumber() const { return type_ == Type::Number; }
    bool isString() const { return type_ == Type::String; }
    bool isArray() const { return type_ == Type::Array; }
    bool isObject() const { return type_ == Type::Object; }

    double asNumber(double def = 0.0) const { return type_ == Type::Number ? num_ : def; }
    bool asBool(bool def = false) const { return type_ == Type::Bool ? bool_ : def; }
    const std::string& asString() const { return str_; }

    size_t size() const { return type_ == Type::Array ? arr_.size() : obj_.size(); }
    const JsonValue& operator[](size_t i) const;
    const JsonValue& operator[](const std::string& key) const;
    bool has(const std::string& key) const { return obj_.count(key) != 0; }

    // 便利アクセサ (キーが無い/型違いなら既定値)
    double num(const std::string& key, double def) const;
    std::string str(const std::string& key, const std::string& def) const;
    bool boolean(const std::string& key, bool def) const;
    std::vector<double> numArray(const std::string& key) const;

    static const JsonValue& nullValue();

private:
    friend class JsonParser;
    Type type_ = Type::Null;
    double num_ = 0;
    bool bool_ = false;
    std::string str_;
    std::vector<JsonValue> arr_;
    std::map<std::string, JsonValue> obj_;
};

// 失敗時は false と error にメッセージ (位置付き) を返す
bool parseJson(const std::string& text, JsonValue& out, std::string& error);

}  // namespace es
