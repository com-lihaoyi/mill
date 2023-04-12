import javax.swing.*;
import java.awt.event.*;
import java.awt.image.*;
import java.awt.geom.*;
import java.awt.*;
import java.util.Vector;

class GUI{
    static int X = 100;
    static int Y = 100;
    static int Speed = 100;
    static int randomInt(int x, int y){
        return x + y;
    }
    static void close(String s, String s2){
        throw new RuntimeException(s);
    }
}

class Tetris extends JFrame implements KeyListener, ActionListener{

    final int X = GUI.X;
    final int Y = GUI.Y;
    final int BlockWidth = 20;
    final int Width = 13;
    final int Height = Y / BlockWidth;
    final int LeftBorder = (X - BlockWidth * Width) / 2;
    final int ShapeSize = 7;
    int SoundCounter = 0;
    Position[][] Map = new Position[Width][Height];
    Timer Stopwatch = new Timer((int)(GUI.Speed * 15), this);
    Background Area;
    int Counter = 0;
    int LinesCleared = 0;
//    LoopingMidi Music = new LoopingMidi("Tetris.mid", 0);
    Timer MoveTimer = new Timer((int)(GUI.Speed * 60), new ActionListener(){
        public void actionPerformed(ActionEvent evt) {
            try{
                if(Left == true){
                    MovingBlock.move(-1, 0, true);
                }
                if(Right == true){
                    MovingBlock.move(1, 0, true);
                }
                if(Down == true){
                    MovingBlock.move(0, 1, true);
                }
            }catch(NullPointerException e){}
        }
    });
    public void moveSound(){
        if(SoundCounter == 0){
//            GUI.playSound(true, "MOVPIECE.wav", 0, 2);
//            GUI.playSound(true, "MOVPIECE.wav", 0, 2);
            SoundCounter = 3;
        }
    }
    public void printSquare(int POSX, int POSY, Color Tint){
        int INTX = LeftBorder + POSX * BlockWidth;
        int INTY = POSY * BlockWidth;
        Area.Painter.setColor(new Color(Tint.getRed() / 2, Tint.getGreen() / 2, Tint.getBlue() / 2));
        Area.Painter.fillRect(INTX, INTY, BlockWidth, BlockWidth);
        Area.Painter.setColor(Tint);
        Area.Painter.drawRect(INTX + 1, INTY + 1, BlockWidth - 2, BlockWidth - 2);
    }
    class Position{
        Color Tint;
        boolean Filled;
        boolean Final;
        public Position(){
            Tint = Color.black;
            Filled = false;
            Final = false;
        }
        public void setPosition(Color tTint, boolean tFilled, boolean tFinal){
            Tint = tTint;
            Filled = tFilled;
            Final = tFinal;
        }
    }
    class Block{
        int[][] Shape;
        int Rotation;
        int POSY;
        int POSX;
        int INTX = 0;
        int INTY = 0;
        int Direction = 2;
        boolean Rotateable;
        Color Tint = new Color(0);
        public void imprint(){
            for(int i = 0; i < ShapeSize; i++){
                for(int j = 0; j < ShapeSize; j++){
                    if(Shape[i][j] == 1 || Shape[i][j] == 2){
                        if(j - INTY + POSY < 1){
                            Stopwatch.stop();
                            GUI.close("You lose", "");
                            return;
                        }
                        Map[i - INTX + POSX][j - INTY + POSY].setPosition(Tint, true, true);

                    }
                }
            }

            checkLines();
        }
        public void rotate(int Rotation, boolean Primary){
            if(Primary == true){
                this.print(Color.black);
            }
            moveSound();
            int[][] Temp = new int[ShapeSize][ShapeSize];
            for(int i = 0; i < ShapeSize; i++){
                for(int j = 0; j < ShapeSize; j++){
                    if(Shape[i][j] == 1 || Shape[i][j] == 2){
                        int TEMPX = i - INTX;
                        int TEMPY = j - INTY;
                        if(Rotation == 1){
                            Temp[-TEMPY + INTX][TEMPX + INTY] = Shape[i][j];
                        }else{
                            Temp[TEMPY + INTX][-TEMPX + INTY] = Shape[i][j];
                        }
                    }
                }
            }
            Shape = Temp;
            if(checkOverlap() && (Primary == true)){
                rotate(-Rotation, false);
            }
            if(Primary == true){
                this.print(Tint);
            }
        }
        public boolean checkOverlap(){
            for(int i = 0; i < ShapeSize; i++){
                for(int j = 0; j < ShapeSize; j++){
                    if(Shape[i][j] == 1 || Shape[i][j] == 2){
                        try{
                            if(Map[i - INTX + POSX][j - INTY + POSY].Filled == true){
                                return true;
                            }
                        }catch(ArrayIndexOutOfBoundsException e){return true;}
                    }
                }
            }
            return false;
        }
        public Block(int tShape){
            POSX = Map.length / 2;
            POSY = 1;

            switch(tShape){
                case 1:{int[][] Temp = {{0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 1, 0, 0, 0},
                    {0, 0, 1, 2, 1, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0}};
                    Shape = Temp;
                    Rotateable = true;
                    Tint = Color.red;
                    break;}
                case 2:{int[][] Temp = {{0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 1, 1, 0, 0},
                    {0, 0, 0, 2, 0, 0, 0},
                    {0, 0, 0, 1, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0}};
                    Shape = Temp;
                    Rotateable = true;
                    Tint = Color.blue;
                    break;}
                case 3:{int[][] Temp = {{0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 1, 1, 0, 0, 0},
                    {0, 0, 0, 2, 0, 0, 0},
                    {0, 0, 0, 1, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0}};
                    Shape = Temp;
                    Rotateable = true;
                    Tint = Color.green;
                    break;}
                case 4:{int[][] Temp = {{0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 1, 0, 0, 0},
                    {0, 0, 0, 2, 0, 0, 0},
                    {0, 0, 0, 1, 0, 0, 0},
                    {0, 0, 0, 1, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0}};
                    Shape = Temp;
                    Rotateable = true;
                    Tint = Color.yellow;
                    break;}
                case 5:{int[][] Temp = {{0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 1, 1, 0, 0, 0},
                    {0, 0, 1, 2, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0}};
                    Shape = Temp;
                    Rotateable = false;
                    Tint = Color.white;
                    break;}
                case 6:{int[][] Temp = {{0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 1, 1, 0, 0, 0},
                    {0, 0, 0, 2, 1, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0}};
                    Shape = Temp;
                    Rotateable = true;
                    Tint = Color.magenta;
                    break;}
                case 7:{int[][] Temp = {{0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 1, 1, 0, 0},
                    {0, 0, 1, 2, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 0, 0}};
                    Shape = Temp;
                    Rotateable = true;
                    Tint = Color.cyan;
                    break;}
            }
            for(int i = 0; i < ShapeSize; i++){
                for(int j = 0; j < ShapeSize; j++){
                    if(Shape[i][j] == 2){
                        INTX = i;
                        INTY = j;
                    }
                }
            }
        }
        public void print(Color Tint){

            for(int i = 0; i < ShapeSize; i++){
                for(int j = 0; j < ShapeSize; j++){
                    if(Shape[i][j] == 1 || Shape[i][j] == 2){
                        printSquare(i - INTX + POSX, j - INTY + POSY, Tint);
                    }
                }
            }

        }
        public void move(int MoveX, int MoveY, boolean Manuel){
            boolean BOOLA = false;
            print(Color.black);
            POSY = POSY + MoveY;
            POSX = POSX + MoveX;
            if(Manuel == true){
                moveSound();
            }
            if(checkOverlap() == true){
                POSY = POSY - MoveY;
                POSX = POSX - MoveX;
            }

            print(Tint);
        }
        public boolean checkCollision(){
            for(int i = 0; i < ShapeSize; i++){
                for(int j = 0; j < ShapeSize; j++){
                    if(Shape[i][j] == 1 || Shape[i][j] == 2){
                        try{
                            if((Shape[i][j + 1] != 1) && (Map[i - INTX + POSX][j - INTY + POSY + 1].Filled == true)){
                                return true;
                            }
                        }catch(ArrayIndexOutOfBoundsException e){return true;
                        }//catch(NullPointerException f){}
                    }
                }
            }
            return false;
        }

    }
    Block MovingBlock;
    Block NextBlock;
    int Speed = 0;
    boolean Left;
    boolean Right;
    boolean Down;
    public void initMap(){
        for(int i = 0; i < Map.length; i++){
            for(int j = 0; j < Map[i].length; j++){
                Map[i][j] = new Position();
            }
        }
    }
    public boolean checkLine(int Line){
        for(int j = 0; j < Width; j++){
            if(Map[j][Line].Filled == false){
                return false;
            }
        }
        return true;
    }
    public void clearLine(int Line){
        for(int i = 0; i < Width; i++){
            Map[i][Line] = new Position();
        }

        LinesCleared = LinesCleared + 1;

    }
    public void checkLines(){
        for(int i = 0; i < Height; i++){


            if(checkLine(i) == true){
                clearLine(i);
                moveDown(i);
            }
        }
    }
    public void moveLine(int Line){
        for(int i = 0; i < Width; i++){
            try{
                Map[i][Line].setPosition(Map[i][Line - 1].Tint, Map[i][Line - 1].Filled, Map[i][Line - 1].Final);
                Map[i][Line - 1] = new Position();
                printLine(Line - 1);
                printLine(Line);
            }catch(ArrayIndexOutOfBoundsException e){}
        }
    }
    public void printLine(int Line){
        for(int i = 0; i < Width; i++){
            printSquare(i, Line, Map[i][Line].Tint);
        }
    }
    public void moveDown(int Level){
        for(int j = Level; j >= 0; j--){
            moveLine(j);
        }
    }
    public void drawBorders(){

        Area.Painter.setColor(Color.white);
        Area.Painter.drawRect(0, 0, LeftBorder - 1, Y);
        Area.Painter.drawRect(X - LeftBorder + 1, 0, LeftBorder - 1, Y);
        Area.Painter.drawRect(LeftBorder, Height * BlockWidth, X - 2 * LeftBorder, Y - Height * BlockWidth);
    }
    public void initSounds(){
//        GUI.playSound(false, "MOVPIECE.wav", 0, 2);
    }

    public Tetris(){

        //BASIC INITIALIZATION
        super("Window");
        this.setSize(X, Y);
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setResizable(false);

        //SET CONTENT PANE
        Container contentArea = getContentPane();

        //LAYOUT MANAGER
        //ADD ITEMS TO CONTENT PANE
        Area = new Background();
        initMap();
        drawBorders();
        initSounds();
//        Music.play('s');
        contentArea.add(Area);
        MoveTimer.start();
        Stopwatch.start();
        this.addKeyListener(this);


        //ADD CONTENT PANE AND PACK
        this.setContentPane(contentArea);
        this.show();
        //this.pack();
    }

    //EVENT TRIGGERS
    public void keyPressed(KeyEvent e){
        try{
            switch(e.getKeyCode()){
                case 32: if(MovingBlock.Rotateable)MovingBlock.rotate(1, true); ; break;
                case 39: Right = true;/* MovingBlock.move(1, 0);*/ break;
                case 37: Left = true;/* MovingBlock.move(-1, 0);*/ break;
                case 40: Down = true;/* MovingBlock.move(0, 1);*/ break;

            }
        }catch(NullPointerException u){}
    }
    public void keyReleased(KeyEvent e){
        try{
            switch(e.getKeyCode()){

                case 39:  Right = false; break;
                case 37:  Left = false; break;
                case 40:  Down = false; break;

            }
        }catch(NullPointerException u){}

    }


    public void keyTyped(KeyEvent e){


    }



    public void actionPerformed(ActionEvent e){
        /*
        try{
            if(loop.sequencer.isRunning() == false){
                loop.sequencer.start();
            }
        }catch(Exception f){}
         */
        SoundCounter = Math.max(SoundCounter - 1, 0);
        if(NextBlock == null){
            NextBlock = new Block(GUI.randomInt(1, 7));
        }
        if(MovingBlock == null){
            NextBlock.print(Color.black);
            MovingBlock = NextBlock;
            MovingBlock.POSX = Map.length / 2;
            MovingBlock.POSY = 0;
            NextBlock = new Block(GUI.randomInt(1, 7));
            NextBlock.POSY = 10;
            NextBlock.POSX = 19;
            NextBlock.print(NextBlock.Tint);
        }




        Counter = Counter + 1;
        if(Counter % (20 - LinesCleared / 10) == 0){
            if(MovingBlock.checkCollision() == true){
                MovingBlock.imprint();
                MovingBlock = null;
            }else{
                MovingBlock.move(0, 1, false);
            }

            Counter = 0;
        }

        Area.paintComponent(this.getGraphics());
    }



    public void paint(){
        ((Graphics2D)this.getGraphics()).drawImage(Area.Picture, null, 0, 0);

    }

    class Background extends JPanel{

        BufferedImage Picture = new BufferedImage(X, Y, BufferedImage.TYPE_USHORT_555_RGB);
        Graphics Painter = Picture.getGraphics();


        public void paintComponent(Graphics paint){
            try{
                Graphics2D Painter = Picture.createGraphics();
                Graphics2D Paint = (Graphics2D)(paint);
                Area.Painter.setColor(Color.black);
                Area.Painter.fillRect((int)(LeftBorder * 1.3 + BlockWidth * Width), 90, 125, 15);
                Area.Painter.setColor(Color.white);
                Area.Painter.drawString("Lines Cleared: " + LinesCleared, (int)(LeftBorder * 1.3 + BlockWidth * Width), 100);
                Area.Painter.drawString("Next Block:",  (int)(LeftBorder * 1.35 + BlockWidth * Width), 150);
                Paint.drawImage(Picture, null, 0, 0);
            }catch(NullPointerException e){}
        }
    }

}
