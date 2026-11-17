package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString

/** The FUKUII logo that `Fukuii.printBanner` logs at startup, and the measurements the splash takes from it (spec 027
  * FR-013, research §3.7, C-8). One literal, read by both, so the log and the splash always draw the same art.
  */
object FukuiiBanner:

  /** The banner exactly as `Fukuii.printBanner` logs it: blank rows around a 60-row logo, padded to 130 columns. */
  val Text: String = """
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                            ›ízzzzzí›                                                            
                                                         ›zzzzzízzzzzzz›                                                         
                                                      —ízzzízzzzzÏ6zzzzzzz—                                                      
                                                   —zzzzzzzzzzízzÏÅgÅGzzzzzzz—                                                   
                                   6ÆÅgggÆg     {zzzzzzízzzzízzzzzz6Å6ÆÏzzzzzzzí—                                                
                                 ÆÏ ›í———{—{GÅÏzzzzízzzzzzzzzzzíízzzÏÆüÆüzzzízzzzzz{                                             
                               üÅ ————{—{{—{——GgzzzzzzzzzíízzzzzzzzzzügüÅíízzzzzzzzzzí{                                          
                               Æ›{{{—züÞÇüz{í{{6ÅzzzzzzzzzzzzízzzzzzzzgÇGÞzzzzzzzzzzzzzízz                                       
                              6Þ———{zÅüzz6Æüí———ÞÞzzzzzzzzzzízzííízzzz6G6gzzzzízzzzzzzzzzzzzíÇÅÅÇügÅÆÆ{                          
                              ÆííízzüÆzzzzzÅ6{——{Æzzzzzzzzzzzzzíízzzzíüg6gzzzzzzzzz{ízzzízGÅÇ6gÅ6                                
                             Æg ›————ÞÆzzzzGGí{—{Åüzzzzzízzízzzízzízzz6G6gzzzzzzízzzzzzzÆgÏÅÅzzzzzz                              
                         ÏÆG— ————————6ÅzzíGG{———Åüzzzzí{ízzzzzzzzzzzíGÞÞGzzzzzzzzzzzzÅGÏÅÞzzzzízzzzzí                           
                       —Åí›—————{—{{———ÅÇzzgÇ{—{íÆzzzzzíízzzízízzzzízzÆÏÅüzzí{{zzzzzÞÅÏÅÞzzzzzzzzzzízzzzz                        
                     zÏÅ —6Ç————{—Åz{——6gzÏÆí———G6zzízzízzzzzzzÏ6ÇÞÞÇGg6gzzzzzzzízÏÆüÞÅzzzzzzzízzzzzzzízzzzz                     
                 ›zízzÅ6›{ÆÆ—{———{ÇÆ{——gÞíÅü———ÏÅzzzzzzzzzGÅÆGÏg—››››——íGÆÆÅGzzzzÇÅüÅÏzízzízzzzzzízzízzízzzzzzz—                 
                zzzzzzÆ6—————{—{—z————{ÆÏÏÆÏíí{g6zzzzzÏgÆÇ——{ÞÏ{í{í{{íÏí{{—›—6ÅÅÅÞÞgzzzzzzzzzzzízzzzzzzzzzzzzzzzz                
                zzzzzzüÅ—{——{ÆÆÅGÆí——{ÅÏzGÞí———gÇzízÏÅÅ{—ízzgí{íííí{› ›{ííííí{zÆüÆÆ6zzzzzzzzízí{{zízzzzzzzízzzzzz                
                zzzzzzíüÆÏ———{ÅÇÅ{{—GÆzízÞGÏ——{{ÅÇígÅ{{zzzÏGííí{íí{—  {í{ííííÇÅüÆüíGÆ6zízzzzzzzíízzzzzzzzzzzzzzzz                
                zzzzízzzz6ÅÅÇÏÏÏ6GÆGzízzzzÆüü{—{—ÏgÆÆÅÇzzzígíííí{{{{  ››››{íüÞÆGüz{ííÆgzzzzzzzzzzzzzzzízízzzzzzzz                
                zzzzzzzzzzzzzzzzzzzzzzzízzüÆüÏ{—í{——{zg6zzzzÞíí{   — ›—›—{{íízzí{{í{{íÆGzzízízzzzzzzzzzzzzzzízzzz                
                zzzzzzzzzzzzzzzzízzzzzzzzzzzGÅ6ÏÏzíííüÆÏzzzGíí{{—›— ›{ííííí{í{íííííí{{íÆÏzzzzzzzzzzzzízzzzzzzzzzz                
                zízzzzzzzzzízzízzzzzzzzzzzzízzüÅÆGÇ6ggÏzzzÏ6í{   › ›{ííí{{ííÞG6ÏzÏÇÅÞí{ÆÅzzzzízzzzzzzzzízzzzzzzzz                
                zzzzíííí{zzzzzzzzzzzíízí{ízzzííÅ6zzzÏGÆgÏzÇíí{í{{› {{{{{ííÏíííí{íí{í{gÞ6Åzzzzzz{{zzzzzzzzzzzzzzzz                
                zzzzzzzzzzízzzzzzzzzzzzzízzzízGÅízÏÅÆÆÆÆÆÅÏíí{í{—    ›{ííííÏGgÅgÇ{íí{íÞÅÅzzzzízzzzzzzzzzzzzzzzzzz                
                zzzzzzzzzzzzzízzí{zzzzzzzzzzzzÆízÏÆÆÆÆ6  üÆÞ{ííí{ ›{{íííííGÆgÅÆÆÆÆÆG{í{íÆgzzzzzzzzzzzzzízzízzzzzzz                
                zízzízzzzzzzzzzízízzzzízzíízz6Æ{zgÆÆÆ{  gÆg{í{{{›{ííí{íÏÆÞ   6ÆÆÆÅgGí{{ÅGzíízzzzzzzízzzzíííízzzzz                
                zzzzzzzzízzzzízzzzzzízzzzízzzÇÆ—zÆÆÆÆÆÆÆÆÆÞí{íí{íííííízÆÆ6   ÆÆÆÆÅGg{í{ÆGzzzzízzzzzzzzzzzzízzzzzz                
                zzzzzzízzzzzzzííüÇÞ6zzzzzzzzz6Æ{zÅÅÆÆÆÆÆÆÆÏíí{íííí{{{íÇÆÆÆÆÆÆÆÆÆÆGGg{{íÆüízzzzízzzzzzzízzzzzzzzíz                
                zzzízzzzízzzzgÆÆÆz››gÆüzzzzzzzÅzzÇÆgÆÆÆÞÆÇ{{{{ííí{íí{íÞÆÆÆÆÆÆÆÆÆgÞÅüí{ÞÅzzzzzííízzízzzzzzízzízzzz                
                zízzzízzzíÏÅÅí›{ííÅí{›ÅÅzzízzz6ÆÏz6ÆÅgÅÆÏííííí{íííí{í{6ÆÆÆÆÆÆGÅGÞÅÞííÏÆzzzzízzzzzzzzzzzzzzzzzzzzz                
                zzzzzzzízÅÅ››í{{íí6Ïí{—GÆzzzzzzÇÆ6zzzGííííííí{íí{í{ííí{GÅgÅÅgÞÞGÅü{í6Æüzzzzzzzzzzzzzízzzzzzzzzízz                
                zzzzzzíÏÆz›íí{í{{íÏ6ííí{GÆzízzzzzÅÆ6ÞÏííí{Þgü{{zÇÅÅÞíí{íígÆÆÆÆGí{{ÏgÅzzzzzzzzzzzízízzzzízzzzzzízz                
                zzzzzz6Å—›{íí{íí{íÅGíííííggzzízzzzzüÅÞíííízÅGÅG666ÇGííííííí{{íízüÞÆÇzzzzzzzzÞgGzzzzzzízíízzzzzzzz                
                zzzzzüÅ››ííí{íí{íÅÅüí{í{í{Å6zzzízzzzzÅgí{í{ÇgÏÏÏÏÏÅí{íí{zÏÏüüüÇÅÅÇzzzzzzzzÅÅ{zÆzízzzzzz{{zzzzzzzz                
                zzzzzÆz›íí{{{íííÅÆgüz{íííííÆÏzzízzzzzzÞÆÇííízÅÆÆÅüí{{ízüüGÅÆÆÅüzzzzzzzzzÅÆ—zügÅzzzzzízízzzzzzzzzz                
                zzízGg›{{ííí{íÅgzÇÆüüíí{{{{6Æzzzí{ízzzzzÇÆÅÞüí{íízzÏüÇgÆÅÅgzzzzzzzzzzzÇÅííüüüÅzzzzíízzzzzzzzzzzzz                
                zzzzÆz—{í{{íÏüÆ6íÞÆüüí{íííí{gGzzzzzzzzí6ÆÅÆÅÞgÆÅÅÅÅÅgGGGGÆgÞÞÅÅzzzzzzÆG{üüüüGÆzzzzízzzzzzízzzzzzz                
                zzzÏÅ›í{íí{6GÅÅGÏÆüüüüí{{{íí{ÅÏzzzzzízgGÇggÏüüÆÆÅgGggÅÆÅGüü6ÇÇGgzzzÏÆzzüüüüüÆzzzzzzzzííízízzzzzzz                
                zzzÏÅ›íííüÆüÏzzízzÅÅüüÏíííííí6ÆzzzzÇÅÅÆÇÇÆíííÏÞgÞGGÞGÅGüüÏÇÆGGÆgzzÇÅ{Ïüüüü6ÅgzzzzzzzGÆüÅÏzzzzzzzz                
                zzzÏÆ›{{zÅzzízzzízÆÅ6üüz{íííííg6GÆgzüüÆÞgÞíí{ííGzüüíííí{{ÏÅ6Ïz6ÆzÞÅ{üüüüÇÇÞÅzzízíÏÅg—›{Åüzzzzzzíz                
                zzzzÆGííÆÞzzzízzzíÅgüüüüÏ{{í{í6ÆÏ{{ízüügÆü{í{{Þ{í{íí{ííííÆÇ66ÅÆÆgÅzüüüÇÇÇÇÆzzzzÅÆ6Åg{{íÅzzízzzzzz                
                zzzzzÆzzÅízízzzízzzzÞÆGüüüzíí{6ÇÅzÏüüGÆÞÅüííízÇ{ííí{ííí{ÏÆGÞg{ízÆ6üü6ÇÇÇÇÆüügÆ6››—{íÞÞÏÆzzzz{ízzz                
                zízzzÏÆÇÅzzízzzzízzzzíGÆÇüüüü6ÅüÆ6ÅÆGzzzÞgíí{Çz{ííííí{{zÅÇgÆÅÏ{ízÆÇ6ÇÇÇÞÆÆgí››ÏÆííí{{6ÆGízzzzzzzz                
                zzzízízgÆzzzízzzzzzzzzzzÏÅÆÅÅggÆÅüzzzzzzzÇÅ6zGz{ííí{í{ÞGÇÇÞÅggÏíízÆÅÆÆÇ—››—{í{ííÞÞíííüÆzzzzzzzzzz                
                zzzzzzzzzzzzzzí{ízzzízzzzzzzzízzzzzzzízzzzüÆÇÇÇGÅgÞGÅGÇÇÇGÅÇÇgÅüÏízÆz{ízÇzííí{íí{6ÞÏüÅÇzzzzzzzzzz                
                  ízzzzízzzzzzzzzzíízzzzzzzzzzzÏÅÅÆgzzzízzzggÇÇÇÇÇÇÅÇÇÇÇÆÅGÇÇÇÞÆÅGgÅÅ{{íííÇí{{ííí{ÅüÅÅzzzzzzzzí                  
                     zzzzzzzzzzígÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÅÅÆÆÆÆÆÆÆÆÆÆÆÆgÆÆÆÆÆÆÆÆÆÆÆÆÅÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆÆüüGÅÅzzzzzzí                     
                        {zzízzzzÆÅ         Æ—   gÆÆÆ   zÆÇ   ÇÆÆü    Æg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆüÅgzzzz{                        
                           ízzízÆÅ        ›Æ—   gÆÆÆ   zÆÇ   ÇÆz   ›ÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆÆÞzzí                           
                              ízÆÅ   GÆÆÆÆÆÆ—   gÆÆÆ   zÆÇ   Ç{   {ÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆzí                              
                                ÆÅ   —íí{ÏÆÆ—   gÆÆÆ   zÆÇ       zÆÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   ÏÆÆ                                
                                ÆÅ        ÆÆ—   gÆÆÆ   zÆÇ       6ÆÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÅ   GÅÅÅÅÆÆ—   gÆÆÆ   zÆÇ   6    ÏÆÆÆg   ›ÆÆÆÏ   ÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÅ   GÆÅÏÏGÆÏ   zÆÆz   6ÆÇ   ÇÆ    íÆÆÆ    GÆg    ÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÅ   GÆg   ÆÆ         —ÆÆÇ   ÇÆÆ›   ›ÆÆÇ         GÆÆÇ   ÆÆÅ   zÆÆ                                
                                ÆÆGGGÅÆÏ    ÅÆÆGÇÏÏÇGÆÆÆÆÆÞÞGÅÆÆÆGGGGÅÆÆÆgÞüÏüÞÅÆÆÆÆÅGGGÆÆÆGGGÆÆÅ                                
                                 í666ü        zÇGggggÞzzzÞGGGGÏzüGÆGGGÞÇüGgÅÅgG6—   z666Ï —ü66ü›                                 
                                                      —zzzzzzzzzzzÆíí6gzzz—                                                      
                                                          zzzzzzzzgÇÏÆÏ›                                                         
                                                             ízzzzzÅÅÞ                                                           
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 
                                                                                                                                 """

  /** The logo's rows without the padding: the non-blank rows, their common indent and their trailing spaces removed. */
  val Logo: Seq[String] =
    val rows = Text.linesIterator.toSeq.filter(_.trim.nonEmpty)
    val indent = rows.map(_.takeWhile(_ == ' ').length).min
    rows.map(_.drop(indent).stripTrailing())

  /** The trimmed logo's width in terminal columns, measured as the frame measures it. */
  val Cols: Int = Logo.map(row => new AttributedString(row).columnLength()).max

  /** The wordmark plate: the band whose negative space spells FUKUII, from its top edge to its bottom edge. The three
    * rows under it are the creature's tail, not part of the wordmark, so they are not counted.
    */
  val WordmarkRows: Range = 46 to 56

  /** The creature alone: the logo without its wordmark rows (the cropped splash, Q2). */
  val Creature: Seq[String] = Logo.zipWithIndex.collect { case (row, i) if !WordmarkRows.contains(i) => row }
