import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLCanvas;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;

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

        ViewerPanel(ArrayList<PointN> pts){
            this.pts = pts;
            setBackground(Color.WHITE);
            
            addGLEventListener(this);

            addMouseListener(this);   
            addMouseMotionListener(this);
            addMouseWheelListener(this);
            computeCenterAndScale();
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
            GL2 gl = drawable.getGL().getGL2();
            gl.glClearColor(1f, 1f, 1f, 1f);
            gl.glEnable(GL.GL_DEPTH_TEST);

            
        }

        public void reshape(GLAutoDrawable drawable, int x, int y, int w, int h) {
            GL2 gl = drawable.getGL().getGL2();
            if (h == 0) h = 1;

            gl.glViewport(0, 0, w, h);
            gl.glMatrixMode(GL2.GL_PROJECTION);
            gl.glLoadIdentity();

            double aspect = (double) w / h;
            double fovy = 60.0;
            double near = 0.01;
            double far = 100.0;

            // perspective
            double f = 1.0 / Math.tan(Math.toRadians(fovy) / 2.0);
            double[] m = new double[16];
            m[0] = f / aspect;
            m[5] = f;
            m[10] = (far + near) / (near - far);
            m[11] = -1.0;
            m[14] = (2.0 * far * near) / (near - far);
            m[15] = 0.0;
            gl.glLoadMatrixd(m, 0);

            gl.glMatrixMode(GL2.GL_MODELVIEW);
        }

        public void display(GLAutoDrawable drawable) {
            GL2 gl = drawable.getGL().getGL2();

            gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
            gl.glLoadIdentity();

            // camera
            gl.glTranslated(0, 0, -zoom);
            gl.glRotated(Math.toDegrees(pitch), 1, 0, 0);
            gl.glRotated(Math.toDegrees(yaw), 0, 1, 0);

            gl.glScaled(scale, scale, scale);
            gl.glTranslated(-center.x, -center.y, -center.z);

            // drawing mesh
            if (mesh != null) {
                gl.glColor4f(0.6f, 0.6f, 0.9f, 0.3f);
                gl.glBegin(GL2.GL_TRIANGLES);
                for (int[] f : mesh.F) {
                    Vector3 a = mesh.V.get(f[0]);
                    Vector3 b = mesh.V.get(f[1]);
                    Vector3 c = mesh.V.get(f[2]);
                    gl.glVertex3d(a.x, a.y, a.z);
                    gl.glVertex3d(b.x, b.y, b.z);
                    gl.glVertex3d(c.x, c.y, c.z);
                    
                }
                gl.glEnd();
            }

            // ---- edges ----
            gl.glDisable(GL2.GL_BLEND);
            gl.glColor3f(0f, 0f, 0f);   // Black
            gl.glLineWidth(3.0f);

            gl.glBegin(GL2.GL_LINES);
            for (int[] f : mesh.F) {
                Vector3 a = mesh.V.get(f[0]);
                Vector3 b = mesh.V.get(f[1]);
                Vector3 c = mesh.V.get(f[2]);

                gl.glVertex3d(a.x, a.y, a.z);
                gl.glVertex3d(b.x, b.y, b.z);

                gl.glVertex3d(b.x, b.y, b.z);
                gl.glVertex3d(c.x, c.y, c.z);

                gl.glVertex3d(c.x, c.y, c.z);
                gl.glVertex3d(a.x, a.y, a.z);
            }
            gl.glEnd();

            // drawing points
            if (pts != null) {
                gl.glDisable(GL.GL_DEPTH_TEST);
                gl.glPointSize(5.0f);
                gl.glColor4f(0.05f, 0.05f, 0.05f, 0.9f);

                gl.glBegin(GL.GL_POINTS);
                for (PointN pn : pts) {
                    Vector3 p = pn.p;
                    gl.glVertex3d(p.x, p.y, p.z);
                }
                gl.glEnd();

                gl.glEnable(GL.GL_DEPTH_TEST);
            }
        }

        public void dispose(GLAutoDrawable drawable) {}

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
            repaint();
        }
}

