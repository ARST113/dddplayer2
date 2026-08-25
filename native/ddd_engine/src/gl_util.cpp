/*
 * gl_util.cpp — реализация (см. gl_util.h).
 */
#include "gl_util.h"

#include <GLES3/gl3.h>

#include <vector>

#include "ddd_log.h"

namespace ddd {

namespace {

const char *GlErrorString(GLenum e) {
    switch (e) {
        case GL_NO_ERROR: return "GL_NO_ERROR";
        case GL_INVALID_ENUM: return "GL_INVALID_ENUM";
        case GL_INVALID_VALUE: return "GL_INVALID_VALUE";
        case GL_INVALID_OPERATION: return "GL_INVALID_OPERATION";
        case GL_INVALID_FRAMEBUFFER_OPERATION: return "GL_INVALID_FRAMEBUFFER_OPERATION";
        case GL_OUT_OF_MEMORY: return "GL_OUT_OF_MEMORY";
        default: return "GL_UNKNOWN";
    }
}

unsigned CompileShader(GLenum type, const char *src, const char *tag) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) {
        DDD_LOGE("gl: glCreateShader(%s) вернул 0", tag);
        return 0;
    }
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);

    GLint status = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        GLint len = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(len > 1 ? static_cast<size_t>(len) : 1, '\0');
        glGetShaderInfoLog(shader, static_cast<GLsizei>(log.size()), nullptr, log.data());
        DDD_LOGE("gl: %s %s не скомпилировался: %s", tag,
                 type == GL_VERTEX_SHADER ? "vertex" : "fragment", log.data());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

}  // namespace

unsigned BuildGlProgram(const char *vertex_src, const char *fragment_src, const char *tag) {
    const GLuint vs = CompileShader(GL_VERTEX_SHADER, vertex_src, tag);
    if (vs == 0) return 0;
    const GLuint fs = CompileShader(GL_FRAGMENT_SHADER, fragment_src, tag);
    if (fs == 0) {
        glDeleteShader(vs);
        return 0;
    }

    const GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);

    // Шейдеры можно удалять сразу после линковки: программа держит на них
    // ссылку, а сами объекты больше не нужны.
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint status = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        GLint len = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &len);
        std::vector<char> log(len > 1 ? static_cast<size_t>(len) : 1, '\0');
        glGetProgramInfoLog(program, static_cast<GLsizei>(log.size()), nullptr, log.data());
        DDD_LOGE("gl: %s не слинковался: %s", tag, log.data());
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

bool CheckGlError(const char *what) {
    bool clean = true;
    // Цикл, а не одно чтение: ошибок в очереди может быть несколько, и первая
    // не обязательно самая информативная.
    for (GLenum e = glGetError(); e != GL_NO_ERROR; e = glGetError()) {
        DDD_LOGE("gl: %s — %s (0x%04x)", what, GlErrorString(e), static_cast<unsigned>(e));
        clean = false;
    }
    return clean;
}

}  // namespace ddd
