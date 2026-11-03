package app.holst;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("holst", Context.MODE_PRIVATE);
    }

    String code() {
        String code = sp.getString("code", null);
        if (code == null) {
            code = Room.newCode();
            sp.edit().putString("code", code).apply();
        }
        return code;
    }

    void setCode(String code) {
        sp.edit().putString("code", code).apply();
    }

    boolean enabled() {
        return sp.getBoolean("enabled", false);
    }

    void setEnabled(boolean v) {
        sp.edit().putBoolean("enabled", v).apply();
    }

    /** Пользователь уже выбрал, что лежит под рисунками: картинку или чистый лист. */
    boolean chosen() {
        return sp.getBoolean("chosen", false);
    }

    void setChosen(boolean v) {
        sp.edit().putBoolean("chosen", v).apply();
    }

    int colorIndex() {
        return sp.getInt("color", 0);
    }

    void setColorIndex(int v) {
        sp.edit().putInt("color", v).apply();
    }

    int sizeIndex() {
        return sp.getInt("size", 1);
    }

    void setSizeIndex(int v) {
        sp.edit().putInt("size", v).apply();
    }

    boolean eraser() {
        return sp.getBoolean("eraser", false);
    }

    void setEraser(boolean v) {
        sp.edit().putBoolean("eraser", v).apply();
    }
}
