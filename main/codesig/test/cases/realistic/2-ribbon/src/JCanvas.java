import java.io.*;
import java.util.*;
import java.awt.*;
import javax.swing.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
class JCanvas extends JPanel{
    private BufferedImage Picture;
    Graphics2D Painter;
    private Graphics Paint;

    public JCanvas(){
        Picture = new BufferedImage(800, 600, BufferedImage.TYPE_BYTE_INDEXED);
        Painter = Picture.createGraphics();
    }


    public void paintComponent(Graphics g){
        try{

            Paint = this.getGraphics();
            g.drawImage(Picture, 0, 0, null);

        }catch(NullPointerException e){
        }
    }
}
