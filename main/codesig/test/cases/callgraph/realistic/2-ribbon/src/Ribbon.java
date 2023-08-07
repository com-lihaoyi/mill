// Taken from https://github.com/lihaoyi/Java-Games/blob/f5a47a07993ea6a6504c071792ec37b9e62a49f8/Ribbon4K/Ribbon.java

import java.awt.*;
import java.util.*;
import java.awt.event.*;
import java.awt.geom.*;
import javax.swing.*;
import javax.swing.Timer;

public class Ribbon{
    public static void main(String[] args){
        new RibbonGame();

    }
    public static double square(double INPUT){
        return INPUT * INPUT;
    }
    public static double length(Line2D L){
        return Math.sqrt(square(L.getX1() - L.getX2()) + square(L.getY1() - L.getY2()));
    }
    public static int randomInt(int INTA, int INTB){
        return ((int)Math.ceil(Math.random() * (Math.max(INTA, INTB) - Math.min(INTA, INTB) + 1) + Math.min(INTA, INTB) - 1));
    }
    public static double cap(double Min, double Current, double Max){
        if(Current < Min){
            return Min;
        }

        if(Current > Max){
            return Max;
        }
        return Current;
    }
}
class SnakeVector extends Vector{
    public Point2D.Float point(int A){
        return (Point2D.Float)elementAt(A);
    }
    public Snake snake(int A){
        return (Snake)elementAt(A);
    }
    public Apple apple(int A){
        return (Apple)elementAt(A);
    }
}
class Snake{
    final SnakeVector Points = new SnakeVector();
    final Point[] LeftPoints = new Point[1024];
    final Point[] RightPoints = new Point[1024];

    final float Width = 5;

    final float PointStep = 4;

    float Length;
    float Speed;

    float ActualLength;
    float Theta;

    int N;
    int WaveCounter = 0;
    boolean Left;
    boolean Right;
    float TurboCounter = 5;
    boolean TurboOn = false;

    boolean Dead = false;
    final float TurnSpeed = (float)Math.PI / 1.5f;

    final SnakeVector Home;
    final Color Tint;

    public Snake(SnakeVector home, float theta, Point2D.Float Position, Color tint){
        Home = home;
        Length = 150;
        Speed = 50;
        Theta = theta;
        Tint = tint;
        Points.add(Position);
        Points.add(Position.clone());
        //     Points.point(0).x += 0.1 * Math.cos(Theta);
        //   Points.point(0).y += 0.1 * Math.sin(Theta);
        for(int i = 0; i < LeftPoints.length; i++){
            LeftPoints[i] = new Point();
            RightPoints[i] = new Point();
        }
        N = Points.size() - 1;
    }

    public void move(double FramesPerSecond){

        Theta += TurnSpeed / FramesPerSecond * ((Left ? -1 : 0) + (Right ? 1 : 0));

        if(TurboOn == false){
            TurboCounter += 1.0 / FramesPerSecond;
        }else{
            TurboCounter -= 1.0 / FramesPerSecond;
        }
        TurboCounter = (float)Ribbon.cap(0, TurboCounter, 5);
        float DS = (float)((TurboOn == true && TurboCounter >= 0 ? (Speed * 1.75) : (Speed)) / FramesPerSecond);

        Points.point(0).x += (float)(DS * Math.cos(Theta));
        Points.point(0).y += (float)(DS * Math.sin(Theta));
        ;
        WaveCounter += DS;


        if(Points.point(0).distance(Points.point(1)) >= PointStep){
            Points.insertElementAt(Points.point(0).clone(), 0);
            Points.point(0).x += 0.1 * Math.cos(Theta);
            Points.point(0).y += 0.1 * Math.sin(Theta);
        }


        if(Dead){
            Length -= 8;
        }
        shorten();
        N = Points.size() - 1;
        calcLength();

        if(Length <= 0){
            Home.remove(this);
        }
        //  printPoints();
    }
    final public void thicken(){

        for(int i = 1; i < Points.size(); i++){
            float DX = Points.point(i).x - Points.point(i - 1).x;
            float DY = Points.point(i).y - Points.point(i - 1).y;
            float FX = DY / (float)Math.sqrt(DX * DX + DY * DY) * Width * (float)Math.sin((PointStep * i + WaveCounter) / Math.PI / 8);
            float FY = -DX / (float)Math.sqrt(DX * DX + DY * DY) * Width * (float)Math.sin((PointStep * i + WaveCounter) / Math.PI / 8);

            LeftPoints[i].x = (int)(Points.point(i).x + FX);
            LeftPoints[i].y = (int)(Points.point(i).y + FY);
            RightPoints[i].x = (int)(Points.point(i).x - FX);
            RightPoints[i].y = (int)(Points.point(i).y - FY);
            if(i == 1){
                i--;
                LeftPoints[i].x = (int)(Points.point(i).x + FX);
                LeftPoints[i].y = (int)(Points.point(i).y + FY);
                RightPoints[i].x = (int)(Points.point(i).x - FX);
                RightPoints[i].y = (int)(Points.point(i).y - FY);
                i++;
            }
        }
    }
    final private void calcLength(){
        ActualLength = 0;
        for(int i = 1; i < Points.size(); i++){
            ActualLength += Points.point(i).distance(Points.point(i - 1));
        }
    }

    final public void shorten(){
        calcLength();
        float LengthDefect = ActualLength - Length;
        if(LengthDefect > 0){
            if(Points.size() > 2 && LengthDefect > Points.point(Points.size() - 2).distance(Points.point(Points.size() - 1))){
                Points.removeElementAt(Points.size() - 1);
                shorten();
            }else{

                float DX = Points.point(Points.size() - 2).x  - Points.point(Points.size() - 1).x;
                float DY = Points.point(Points.size() - 2).y  - Points.point(Points.size() - 1).y;

                float Multiplier = (float)(LengthDefect / Math.sqrt(DX * DX + DY * DY));
                Points.point(Points.size() - 1).x += DX * Multiplier;
                Points.point(Points.size() - 1).y += DY * Multiplier;
            }
        }
        return;
    }
    final public boolean checkCollision(Rectangle Target){


        if(!Target.contains(LeftPoints[0]) || !Target.contains(RightPoints[0])){
            return true;
        }else{
            return false;
        }
    }

    final public boolean checkCollision(Snake Target){

        Line2D.Float LineA = new Line2D.Float();
        Line2D.Float LineB = new Line2D.Float();
        LineA.setLine(LeftPoints[0], RightPoints[0]);
        for(int i = 1; i <= Target.N; i++){
            if(Target == this && (i == 1 || i == 2)){
                continue;
            }
            LineB.setLine(Target.RightPoints[i], Target.RightPoints[i - 1]);
            if(LineB.intersectsLine(LineA) && Ribbon.length(LineA) > 0 && Ribbon.length(LineB) > 0){
                System.out.println("DAI1");
                return true;

            }
            LineB.setLine(Target.LeftPoints[i], Target.LeftPoints[i - 1]);
            if(LineB.intersectsLine(LineA) && Ribbon.length(LineA) > 0 && Ribbon.length(LineB) > 0){
                System.out.println("DAI2");
                return true;
            }

        }
        LineB.setLine(Target.LeftPoints[N], Target.RightPoints[N]);
        if(LineB.intersectsLine(LineA) && Ribbon.length(LineA) > 0 && Ribbon.length(LineB) > 0 && ActualLength > 2){
            System.out.println("DAI3");
            return true;
        }
        LineB.setLine(Target.LeftPoints[0], Target.RightPoints[0]);
        if(Target != this && LineB.intersectsLine(LineA) && Ribbon.length(LineA) > 0 && Ribbon.length(LineB) > 0){
            System.out.println("DAI4");
            return true;
        }
        return false;
    }
    public void print(Graphics2D Painter){
        Painter.setColor(Tint);
        Painter.drawLine(LeftPoints[0].x, LeftPoints[0].y, RightPoints[0].x, RightPoints[0].y);
        Painter.drawLine(LeftPoints[N].x, LeftPoints[N].y, RightPoints[N].x, RightPoints[N].y);
        for(int i = 1; i <= N; i++){
            Painter.drawLine((int)LeftPoints[i].x, (int)LeftPoints[i].y, (int)LeftPoints[i - 1].x, (int)LeftPoints[i - 1].y);
            Painter.drawLine((int)RightPoints[i].x, (int)RightPoints[i].y, (int)RightPoints[i - 1].x, (int)RightPoints[i - 1].y);
            //Painter.drawLine((int)Points.point(i).x, (int)Points.point(i).y, (int)Points.point(i - 1).x, (int)Points.point(i - 1).y);
        }
    }
}
class Apple{
    final Point2D.Float Position;
    final Point2D.Float Velocity;
    final Color Tint;
    final int Points;
    final int Radius = 4;
    final SnakeVector Home;
    public Apple(SnakeVector home, int Level, Rectangle Boundary){
        Home = home;
        int V = 0;
        switch(Level){
            case 1:
                Tint = Color.red;
                Points = 15;
                V = 0;
                break;
            case 2:
                Tint = Color.blue;
                Points = 45;
                V = 50;
                break;
            case 3:
                Tint = Color.cyan;
                Points = 75;
                V = 100;
                break;
            default:
                Tint = Color.white;
                Points = 1;
        }

        Velocity = new Point2D.Float((Ribbon.randomInt(0, 1) * 2 - 1) * V, (Ribbon.randomInt(0, 1) * 2 - 1) * V);
        Position = new Point2D.Float(Ribbon.randomInt(Radius + Boundary.x, Boundary.width + Boundary.x - Radius), Ribbon.randomInt(Radius + Boundary.y, Boundary.height + Boundary.y - Radius));
    }
    final public void move(double FramesPerSecond){
        Position.x += Velocity.x / FramesPerSecond;
        Position.y += Velocity.y / FramesPerSecond;
    }

    final public void tryBoundaries(Rectangle Boundary){
        if(Position.x - Radius < Boundary.x){
            Velocity.x = Math.abs(Velocity.x);
        }
        if(Position.y - Radius < Boundary.y){
            Velocity.y = Math.abs(Velocity.y);
        }
        if(Position.x + Radius > Boundary.width + Boundary.x){
            Velocity.x = -Math.abs(Velocity.x);
        }
        if(Position.y + Radius > Boundary.height + Boundary.y){
            Velocity.y = -Math.abs(Velocity.y);
        }
    }
    final public void tryEat(Snake Target){
        if(Line2D.Float.ptSegDist(Target.LeftPoints[0].x, Target.LeftPoints[0].y, Target.RightPoints[0].x, Target.RightPoints[0].y, Position.x, Position.y) < Radius){
            Home.remove(this);
            Target.Length += Points;
        }
    }

    final public void tryCollision(Snake Target){

        Line2D.Float TargetLine = new Line2D.Float();
        float Distance = -1;
        int Index = -1;
        for(int i = 1; i < Target.Points.size(); i++){
            TargetLine.setLine(Target.LeftPoints[i - 1], Target.LeftPoints[i]);
            double D = TargetLine.ptSegDist(Position);
            if(D < Radius && (D < Distance || Index == -1)){
                Index = i;
                Distance = (float)D;
            }
        }
        for(int i = 1; i < Target.Points.size(); i++){
            TargetLine.setLine(Target.RightPoints[i - 1], Target.RightPoints[i]);
            double D = TargetLine.ptSegDist(Position);
            if(D < Radius && (D < Distance || Index == -1)){
                Index = i;
                Distance = (float)D;
            }
        }
        TargetLine.setLine(Target.RightPoints[Target.N], Target.LeftPoints[Target.N]);
        double D = TargetLine.ptSegDist(Position);
        if(D < Radius && (D < Distance || Index == -1)){
            Index = -2;
            Distance = (float)D;
        }
        if(Index != -1){
            if(Index == -2){
                TargetLine.setLine(Target.Points.point(Target.N), Target.Points.point(Target.N));
            }else{
                TargetLine.setLine(Target.Points.point(Index - 1), Target.Points.point(Index));
            }
            Point2D.Float Average = new Point2D.Float((float)(TargetLine.getX1() + TargetLine.getX2()), (float)(TargetLine.getY1() + TargetLine.getY2()));
            Average.x /= 2; Average.y /= 2;
            Average.x = Position.x - Average.x;
            Average.y = Position.y - Average.y;
            float L = (float)Math.sqrt(Average.x * Average.x + Average.y * Average.y);
            Average.x /= L; Average.y /= L;
            float Dot = Average.x * Velocity.x + Average.y * Velocity.y;
            Dot = (float)Ribbon.cap(-Math.sqrt(Velocity.x * Velocity.x + Velocity.y * Velocity.y), Dot, 0);
            Velocity.x -= 2 * Average.x * Dot; Velocity.y -= 2 * Average.y * Dot;


        }
    }
    final public void print(Graphics2D Painter){
        Painter.setColor(Tint);
        Painter.drawLine((int)Position.x - Radius, (int)Position.y - Radius, (int)Position.x + Radius, (int)Position.y + Radius);
        Painter.drawLine((int)Position.x + Radius, (int)Position.y - Radius, (int)Position.x - Radius, (int)Position.y + Radius);
    }

}

class RibbonGame extends JFrame implements KeyListener, ActionListener{

    final int X = 800;
    final int Y = 600;



    Timer Stopwatch = new Timer(20, this);
    float FramesPerSecond = 50;

    JCanvas Area;

    Rectangle Boundary = new Rectangle(0, 0, X, Y);
    SnakeVector Snakes = new SnakeVector();
    SnakeVector Apples = new SnakeVector();
    Snake Player;


    public void init(){
        Snake S = new Snake(Snakes, 0, new Point2D.Float(X / 2, Y / 2), Color.red);
        Snakes.add(S);
        Player = S;
    }



    public RibbonGame(){
        //BASIC INITIALIZATION
        super("Window");
        this.setSize(X, Y);
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setResizable(false);
        //SET CONTENT PANE
        Container contentArea = getContentPane();
        contentArea.setLayout(null);
        //LAYOUT MANAGER
        //ADD ITEMS TO CONTENT PANE
        Area = new JCanvas();

        contentArea.add(Area);

        Boundary.setBounds(0 + 4, 0 + 4, X - 8, Y - 8);

        this.addKeyListener(this);

        //  initSockets();
        init();
        Stopwatch.start();

        //ADD CONTENT PANE AND PACK
        this.setUndecorated(true);
        this.setContentPane(contentArea);
        this.show();
        //this.pack();
    }

    //EVENT TRIGGERS
    public void keyPressed(KeyEvent e){
        //     System.out.println(e.getKeyChar() + "\t" + LEFT + "\t" + RIGHT + "\t" + FORWARD + "\t" + BACK);
        System.out.println(e.getKeyCode());
        switch(e.getKeyCode()){
            case 38:
                Player.TurboOn = true;
                break;
            case 40:
                Apple B = new Apple(Apples, Ribbon.randomInt(1, 3), Boundary);
                Apples.add(B);
                ; break;
            case 37: Player.Left = true; break;
            case 39: Player.Right = true; break;

        }

    }
    public void keyReleased(KeyEvent e){
        switch(e.getKeyCode()){
            case 38: Player.TurboOn = false;
                ; break;
            case 40: ; break;
            case 37: Player.Left = !true; break;
            case 39: Player.Right = !true; break;
        }
    }


    public void keyTyped(KeyEvent e){

    }


    public void actionPerformed(ActionEvent e){

        Area.Painter.setColor(Color.black);
        Area.Painter.fillRect(0, 0, X, Y);
        System.out.println("GO");

        for(int i = 0; i < Snakes.size(); i++){
            Snakes.snake(i).move(FramesPerSecond);


        }
        for(int i = 0; i < Snakes.size(); i++){
            Snakes.snake(i).thicken();
        }
        for(int i = 0; i < Apples.size(); i++){
            Apples.apple(i).move(FramesPerSecond);
            Apples.apple(i).tryBoundaries(Boundary);
            for(int j = 0; j < Snakes.size(); j++){
                Apples.apple(i).tryCollision(Snakes.snake(j));
            }
        }
        for(int i = 0; i < Apples.size(); i++){;
            for(int j = 0; j < Snakes.size(); j++){
                Apples.apple(i).tryEat(Snakes.snake(j));
            }
        }
        for(int i = 0; i < Snakes.size(); i++){
            if(Snakes.snake(i).checkCollision(Boundary)){
                Snakes.snake(i).Dead = true;
                Snakes.snake(i).Speed = 0;

            }
        }
        for(int i = 0; i < Snakes.size(); i++){
            for(int j = 0; j < Snakes.size(); j++){
                Point CollisionData = null;

                if(Snakes.snake(i).checkCollision(Snakes.snake(j))){
                    Snakes.snake(i).Dead = true;
                    Snakes.snake(i).Speed = 0;

                }

            }
        }
        for(int i = 0; i < Snakes.size(); i++){

            Snakes.snake(i).print(Area.Painter);
        }
        for(int j = 0; j < Apples.size(); j++){
            Apples.apple(j).print(Area.Painter);
        }

        Area.Painter.setColor(Color.white);
        Area.Painter.drawRect(2, 2, X - 4, Y - 4);
        Area.Painter.drawRect(4, 4, X - 8, Y - 8);
        Area.paintComponent(this.getGraphics());

    }
    //Time Considerations
}
