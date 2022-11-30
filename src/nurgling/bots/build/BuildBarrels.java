package nurgling.bots.build;

import haven.Button;
import haven.Coord;
import nurgling.NAlias;
import nurgling.NGameUI;
import nurgling.bots.Bot;
import nurgling.bots.actions.Build;
import nurgling.bots.tools.CraftCommand;
import nurgling.bots.tools.Ingredient;
import nurgling.tools.AreaSelecter;
import nurgling.tools.NArea;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;


public class BuildBarrels extends Bot {
    
    
    public BuildBarrels ( NGameUI gameUI ) {
        super ( gameUI );
        win_title = "BuildBarr";
        win_sz.y = 100;
        
        CraftCommand command = new CraftCommand ();
        command.command = new char[]{ 'b', 'c', 'b' };
        command.name = "Barrel";
        command.ingredients = new ArrayList<Ingredient> ();
        command.ingredients
                .add ( new Ingredient ( new NAlias( new ArrayList<String> ( Arrays.asList ( "board" ) ) ), board_area,
                        5 ) );
        ///Добавление цикла в действия бота
        runActions.add ( new Build( build_area, "barrel", command ) );
    }
    
    
    @Override
    public void initAction ()
            throws InterruptedException {
        super.initAction ();
        int y = 0;
        window.add ( new Button ( window.buttons_size, "Зона строительства" ) {
            @Override
            public void click () {
                gameUI.getMap ().isAreaSelectorEnable = true;
                if ( !m_selection_start.get () ) {
                    m_selection_start.set ( true );
                    new Thread ( new AreaSelecter ( gameUI, _start, m_selection_start, build_area ),
                            "Cont Area Selecter" ).start ();
                }
            }
        }, new Coord ( 0, y ) );
        y += 25;
        window.add ( new Button ( window.buttons_size, "Доски" ) {
            @Override
            public void click () {
                gameUI.getMap ().isAreaSelectorEnable = true;
                if ( !m_selection_start.get () ) {
                    m_selection_start.set ( true );
                    new Thread ( new AreaSelecter( gameUI, _zone1, m_selection_start, board_area ),
                            "Cont Area Selecter" ).start ();
                }
            }
        }, new Coord ( 0, y ) );
        y += 25;
        
        while ( !_start.get () || !_zone1.get () ) {
            Thread.sleep ( 100 );
        }
    }
    
    @Override
    public void endAction () {
        _start.set ( false );
        _zone1.set ( false );
        m_selection_start.set ( false );
        super.endAction ();
    }
    
    private AtomicBoolean _start = new AtomicBoolean ( false );
    private AtomicBoolean _zone1 = new AtomicBoolean ( false );
    private NArea build_area = new NArea ();
    private NArea board_area = new NArea ();
    private AtomicBoolean m_selection_start = new AtomicBoolean ( false );
}
