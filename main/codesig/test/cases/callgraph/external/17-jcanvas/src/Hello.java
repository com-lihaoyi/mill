package hello;

import java.awt.*;
import javax.swing.*;
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

// Note that `JCanvas#<init>` has an edge to `JCanvas#paintComponent` because
// it calls `JPanel#<init>`, which we do not analyze as an external class.
// `JPanel#<init>` has the possibility of calling `JPanel#paintComponent` and
// thus `JCanvas#paintComponent`, and so we record an edge from `JCanvas#<init>`
// to `JCanvas#paintComponent` to account for that possibility

/* expected-direct-call-graph
{
    "hello.JCanvas#<init>()void": [
        "hello.JCanvas#paintComponent(java.awt.Graphics)void"
    ],
    "hello.JCanvas#paintComponent(java.awt.Graphics)void": [
        "hello.JCanvas#paintComponent(java.awt.Graphics)void"
    ]
}
*/

/* expected-transitive-call-graph
{
    "hello.JCanvas#<init>()void": [
        "hello.JCanvas#paintComponent(java.awt.Graphics)void"
    ]
}
*/
