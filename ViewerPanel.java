import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.common.nio.Buffers;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;

public class ViewerPanel extends GLCanvas implements GLEventListener, MouseListener, MouseMotionListener, MouseWheelListener  {

        ArrayList<PointN> pts;
        Vector3 center = new Vector3(0,0,0);
        double scale = 1.0;      // model scale to fit screen
        double yaw = 0.0;        // rotation around Y
        double pitch = 0.0;      // rotation around X
        double zoom = 1.7;       // camera distance factor

        Mesh mesh = null;

        int lastX, lastY;
        boolean dragging = false;

        private int prog = 0;
        private int locMVP = -1;
        private int locColor = -1;

        private int vaoTri = 0, vboTri = 0;
        private int vaoLine = 0, vboLine = 0;
        private int vaoPts = 0, vboPts = 0;

        private int triVertexCount = 0;
        private int lineVertexCount = 0;
        private int ptVertexCount = 0;

        private float[] proj = identity();

        private boolean needUploadMesh = false;
        private boolean needUploadPoints = false;

        private String VS;
        private String FS;

        ViewerPanel(ArrayList<PointN> pts){
            super(new GLCapabilities(GLProfile.get(GLProfile.GL3)));
            this.pts = pts;
            setBackground(Color.WHITE);
            
            addGLEventListener(this);

            addMouseListener(this);   
            addMouseMotionListener(this);
            addMouseWheelListener(this);
            computeCenterAndScale();
            needUploadPoints = true;
        }

        void computeCenterAndScale(){
            if(pts.isEmpty()) return;

            double minx=Double.POSITIVE_INFINITY, miny=Double.POSITIVE_INFINITY, minz=Double.POSITIVE_INFINITY;
            double maxx=Double.NEGATIVE_INFINITY, maxy=Double.NEGATIVE_INFINITY, maxz=Double.NEGATIVE_INFINITY;

            for(PointN pn: pts){
                Vector3 p = pn.p;
                minx = Math.min(minx, p.x); maxx = Math.max(maxx, p.x);
                miny = Math.min(miny, p.y); maxy = Math.max(maxy, p.y);
                minz = Math.min(minz, p.z); maxz = Math.max(maxz, p.z);
            }

            center = new Vector3((minx+maxx)/2.0, (miny+maxy)/2.0, (minz+maxz)/2.0);
            double dx = maxx-minx, dy = maxy-miny, dz = maxz-minz;
            double diag = Math.sqrt(dx*dx + dy*dy + dz*dz);
            if(diag < 1e-12) diag = 1.0;

            // scale so that model roughly fits [-1,1]
            scale = 2.0 / diag;
        }

        Vector3 rotX(Vector3 v, double a){
            double c=Math.cos(a), s=Math.sin(a);
            return new Vector3(v.x, c*v.y - s*v.z, s*v.y + c*v.z);
        }
        Vector3 rotY(Vector3 v, double a){
            double c=Math.cos(a), s=Math.sin(a);
            return new Vector3(c*v.x + s*v.z, v.y, -s*v.x + c*v.z);
        }


        // perspective projection
        Point projection(Vector3 v, int w, int h){
            // camera looks toward -Z, so keep z negative in front
            double fov = 60.0 * Math.PI / 180.0;
            double f = 1.0 / Math.tan(fov/2.0);

            double z = v.z - zoom; // shift camera
            // If z is too close, skip
            if(z > -0.05) return null;

            double px = (v.x * f) / (-z);
            double py = (v.y * f) / (-z);

            int sx = (int)(w/2.0 + px * (w/2.0));
            int sy = (int)(h/2.0 - py * (h/2.0));
            return new Point(sx, sy);
        }


        public void init(GLAutoDrawable drawable) {
            GL3 gl = drawable.getGL().getGL3();

            gl.glClearColor(1f, 1f, 1f, 1f);
            gl.glEnable(GL.GL_DEPTH_TEST);
            
            try {
                VS = new String(Files.readAllBytes(Paths.get("shader.vert")));
                FS = new String(Files.readAllBytes(Paths.get("shader.frag")));
            } catch (IOException e) {
                throw new RuntimeException("Failed to load shader files. Check location: shader.vert / shader.frag", e);
            }

            prog = createProgram(gl, VS, FS);
            locMVP = gl.glGetUniformLocation(prog, "uMVP");
            locColor = gl.glGetUniformLocation(prog, "uColor");

            if (needUploadPoints) {
                uploadPoints(gl);
                needUploadPoints = false;
            }

            if (mesh != null) {
                uploadMesh(gl);
                needUploadMesh = false;
            }

            
        }

        public void reshape(GLAutoDrawable drawable, int x, int y, int w, int h) {
            GL3 gl = drawable.getGL().getGL3();
            if (h == 0) h = 1;

            gl.glViewport(0, 0, w, h);

            float aspect = (float) w / (float) h;
            proj = perspective((float) Math.toRadians(60.0), aspect, 0.01f, 100.0f);
        }

        public void display(GLAutoDrawable drawable) {
            GL3 gl = drawable.getGL().getGL3();

            gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

            if (needUploadMesh && mesh != null) {
                uploadMesh(gl);
                needUploadMesh = false;
            }
            if (needUploadPoints) {
                uploadPoints(gl);
                needUploadPoints = false;
            }

            // Build MVP
            // View = T(0,0,-zoom) * Rx(pitch) * Ry(yaw)
            float[] view = mul(translate(0, 0, (float) -zoom),
                        mul(rotX((float) pitch), rotY((float) yaw)));

            // Model = S(scale) * T(-center)
            float[] model = mul(scale((float) scale),
                        translate((float) -center.x, (float) -center.y, (float) -center.z));

            float[] mvp = mul(proj, mul(view, model));

            gl.glUseProgram(prog);
            gl.glUniformMatrix4fv(locMVP, 1, false, mvp, 0);

            // ---- Triangles ----
            if (vaoTri != 0 && triVertexCount > 0) {
                gl.glEnable(GL.GL_BLEND);
                gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);

                gl.glUniform4f(locColor, 0.6f, 0.6f, 0.9f, 0.30f);
                gl.glBindVertexArray(vaoTri);
                gl.glDrawArrays(GL.GL_TRIANGLES, 0, triVertexCount);
                gl.glBindVertexArray(0);

                gl.glDisable(GL.GL_BLEND);
            }

            // ---- Edges ----
            if (vaoLine != 0 && lineVertexCount > 0) {
                gl.glLineWidth(3.0f);

                gl.glUniform4f(locColor, 0f, 0f, 0f, 1f);
                gl.glBindVertexArray(vaoLine);
                gl.glDrawArrays(GL.GL_LINES, 0, lineVertexCount);
                gl.glBindVertexArray(0);
            }

            // ---- Points ----
            if (vaoPts != 0 && ptVertexCount > 0) {
                gl.glDisable(GL.GL_DEPTH_TEST);
                gl.glPointSize(5.0f);

                gl.glUniform4f(locColor, 0.05f, 0.05f, 0.05f, 0.9f);
                gl.glBindVertexArray(vaoPts);
                gl.glDrawArrays(GL.GL_POINTS, 0, ptVertexCount);
                gl.glBindVertexArray(0);

                gl.glEnable(GL.GL_DEPTH_TEST);
            }

            gl.glUseProgram(0);
        }

        public void dispose(GLAutoDrawable drawable) {

            GL3 gl = drawable.getGL().getGL3();

            if (prog != 0) {
                gl.glDeleteProgram(prog);
                prog = 0;
            }

            deleteVAOVBO(gl, vaoTri, vboTri); vaoTri = vboTri = 0;
            deleteVAOVBO(gl, vaoLine, vboLine); vaoLine = vboLine = 0;
            deleteVAOVBO(gl, vaoPts, vboPts); vaoPts = vboPts = 0;
        }

        private void uploadMesh(GL3 gl) {
            if (mesh == null) return;

            // delete old
            deleteVAOVBO(gl, vaoTri, vboTri); vaoTri = vboTri = 0;
            deleteVAOVBO(gl, vaoLine, vboLine); vaoLine = vboLine = 0;

            // ---- triangles ----
            triVertexCount = mesh.F.size() * 3;
            float[] triPos = new float[triVertexCount * 3];
            int k = 0;
            for (int[] f : mesh.F) {
                Vector3 a = mesh.V.get(f[0]);
                Vector3 b = mesh.V.get(f[1]);
                Vector3 c = mesh.V.get(f[2]);

                triPos[k++] = (float) a.x; triPos[k++] = (float) a.y; triPos[k++] = (float) a.z;
                triPos[k++] = (float) b.x; triPos[k++] = (float) b.y; triPos[k++] = (float) b.z;
                triPos[k++] = (float) c.x; triPos[k++] = (float) c.y; triPos[k++] = (float) c.z;
            }
            int[] ids = new int[1];

            gl.glGenVertexArrays(1, ids, 0);
            vaoTri = ids[0];
            gl.glBindVertexArray(vaoTri);

            gl.glGenBuffers(1, ids, 0);
            vboTri = ids[0];
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vboTri);

            FloatBuffer fb = Buffers.newDirectFloatBuffer(triPos);
            gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) triPos.length * 4L, fb, GL.GL_STATIC_DRAW);

            gl.glEnableVertexAttribArray(0);
            gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 0, 0);

            gl.glBindVertexArray(0);
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);

            // ---- edges----
            lineVertexCount = mesh.F.size() * 6;
            float[] linePos = new float[lineVertexCount * 3];
            k = 0;
            for (int[] f : mesh.F) {
                Vector3 a = mesh.V.get(f[0]);
                Vector3 b = mesh.V.get(f[1]);
                Vector3 c = mesh.V.get(f[2]);

                // a-b
                linePos[k++] = (float) a.x; linePos[k++] = (float) a.y; linePos[k++] = (float) a.z;
                linePos[k++] = (float) b.x; linePos[k++] = (float) b.y; linePos[k++] = (float) b.z;
                // b-c
                linePos[k++] = (float) b.x; linePos[k++] = (float) b.y; linePos[k++] = (float) b.z;
                linePos[k++] = (float) c.x; linePos[k++] = (float) c.y; linePos[k++] = (float) c.z;
                // c-a
                linePos[k++] = (float) c.x; linePos[k++] = (float) c.y; linePos[k++] = (float) c.z;
                linePos[k++] = (float) a.x; linePos[k++] = (float) a.y; linePos[k++] = (float) a.z;
            }

            gl.glGenVertexArrays(1, ids, 0);
            vaoLine = ids[0];
            gl.glBindVertexArray(vaoLine);

            gl.glGenBuffers(1, ids, 0);
            vboLine = ids[0];
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vboLine);

            fb = Buffers.newDirectFloatBuffer(linePos);
            gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) linePos.length * 4L, fb, GL.GL_STATIC_DRAW);

            gl.glEnableVertexAttribArray(0);
            gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 0, 0);

            gl.glBindVertexArray(0);
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        }

        private void uploadPoints(GL3 gl) {
            deleteVAOVBO(gl, vaoPts, vboPts); vaoPts = vboPts = 0;

            if (pts == null || pts.isEmpty()) {
                ptVertexCount = 0;
                return;
            }

            ptVertexCount = pts.size();
            float[] pos = new float[ptVertexCount * 3];
            int k = 0;
            for (PointN pn : pts) {
                Vector3 p = pn.p;
                pos[k++] = (float) p.x;
                pos[k++] = (float) p.y;
                pos[k++] = (float) p.z;
            }

            int[] ids = new int[1];
            gl.glGenVertexArrays(1, ids, 0);
            vaoPts = ids[0];
            gl.glBindVertexArray(vaoPts);

            gl.glGenBuffers(1, ids, 0);
            vboPts = ids[0];
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vboPts);

            FloatBuffer fb = Buffers.newDirectFloatBuffer(pos);
            gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) pos.length * 4L, fb, GL.GL_STATIC_DRAW);

            gl.glEnableVertexAttribArray(0);
            gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 0, 0);

            gl.glBindVertexArray(0);
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        }

        private void deleteVAOVBO(GL3 gl, int vao, int vbo) {
            if (vao != 0) {
                int[] a = { vao };
                gl.glDeleteVertexArrays(1, a, 0);
            }
            if (vbo != 0) {
                int[] b = { vbo };
                gl.glDeleteBuffers(1, b, 0);
            }
        }

        private static int createProgram(GL3 gl, String vsSrc, String fsSrc) {
            int vs = compileShader(gl, GL3.GL_VERTEX_SHADER, vsSrc);
            int fs = compileShader(gl, GL3.GL_FRAGMENT_SHADER, fsSrc);

            int program = gl.glCreateProgram();
            gl.glAttachShader(program, vs);
            gl.glAttachShader(program, fs);
            gl.glLinkProgram(program);

            int[] ok = new int[1];
            gl.glGetProgramiv(program, GL3.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                int[] len = new int[1];
                gl.glGetProgramiv(program, GL3.GL_INFO_LOG_LENGTH, len, 0);
                byte[] log = new byte[Math.max(1, len[0])];
                gl.glGetProgramInfoLog(program, log.length, null, 0, log, 0);
                gl.glDeleteProgram(program);
                gl.glDeleteShader(vs);
                gl.glDeleteShader(fs);
                throw new RuntimeException("Program link failed:\n" + new String(log));
            }

            gl.glDetachShader(program, vs);
            gl.glDetachShader(program, fs);
            gl.glDeleteShader(vs);
            gl.glDeleteShader(fs);
            return program;
        }

        private static int compileShader(GL3 gl, int type, String src) {
            int sh = gl.glCreateShader(type);
            String[] lines = new String[]{ src };
            int[] lens = new int[]{ src.length() };
            gl.glShaderSource(sh, 1, lines, lens, 0);
            gl.glCompileShader(sh);

            int[] ok = new int[1];
            gl.glGetShaderiv(sh, GL3.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                int[] len = new int[1];
                gl.glGetShaderiv(sh, GL3.GL_INFO_LOG_LENGTH, len, 0);
                byte[] log = new byte[Math.max(1, len[0])];
                gl.glGetShaderInfoLog(sh, log.length, null, 0, log, 0);
                gl.glDeleteShader(sh);
                throw new RuntimeException("Shader compile failed:\n" + new String(log));
            }
            return sh;
        }

        private static float[] identity() {
            float[] m = new float[16];
            m[0] = m[5] = m[10] = m[15] = 1f;
            return m;
        }

        // out = a * b  (column-major)
        private static float[] mul(float[] a, float[] b) {
            float[] r = new float[16];
            for (int c = 0; c < 4; c++) {
                for (int r0 = 0; r0 < 4; r0++) {
                    r[c * 4 + r0] =
                        a[0 * 4 + r0] * b[c * 4 + 0] +
                        a[1 * 4 + r0] * b[c * 4 + 1] +
                        a[2 * 4 + r0] * b[c * 4 + 2] +
                        a[3 * 4 + r0] * b[c * 4 + 3];
                }
            }
            return r;
        }

        private static float[] translate(float x, float y, float z) {
            float[] m = identity();
            m[12] = x;
            m[13] = y;
            m[14] = z;
            return m;
        }

        private static float[] scale(float s) {
            float[] m = identity();
            m[0] = s;
            m[5] = s;
            m[10] = s;
            return m;
        }

        private static float[] rotX(float a) {
            float[] m = identity();
            float c = (float) Math.cos(a);
            float s = (float) Math.sin(a);
            m[5] = c;   m[9]  = -s;
            m[6] = s;   m[10] =  c;
            return m;
        }

        private static float[] rotY(float a) {
            float[] m = identity();
            float c = (float) Math.cos(a);
            float s = (float) Math.sin(a);
            m[0] = c;   m[8] = s;
            m[2] = -s;  m[10] = c;
            return m;
        }

        private static float[] perspective(float fovyRad, float aspect, float zNear, float zFar) {
            float f = (float) (1.0 / Math.tan(fovyRad / 2.0));
            float[] m = new float[16];
            m[0] = f / aspect;
            m[5] = f;
            m[10] = (zFar + zNear) / (zNear - zFar);
            m[11] = -1f;
            m[14] = (2f * zFar * zNear) / (zNear - zFar);
            // m[15] = 0 (already)
            return m;
        }

        // ====== mouse controls ======
        public void mousePressed(MouseEvent e){
            dragging = true;
            lastX = e.getX();
            lastY = e.getY();
        }
        public void mouseReleased(MouseEvent e){ dragging = false; }
        public void mouseDragged(MouseEvent e){
            if(!dragging) return;
            int x=e.getX(), y=e.getY();
            int dx = x - lastX;
            int dy = y - lastY;
            lastX = x; lastY = y;

            yaw += dx * 0.01;
            pitch += dy * 0.01;

            repaint();
        }
        public void mouseWheelMoved(MouseWheelEvent e){
            int rot = e.getWheelRotation();
            zoom += rot * 0.2;
            if(zoom < 0.5) zoom = 0.5;
            if(zoom > 20.0) zoom = 20.0;
            repaint();
        }

        public void mouseClicked(MouseEvent e){}
        public void mouseEntered(MouseEvent e){}
        public void mouseExited(MouseEvent e){}
        public void mouseMoved(MouseEvent e){}

        public void setMesh(Mesh m){
            this.mesh = m;
            needUploadMesh = true;
            repaint();
        }
}

