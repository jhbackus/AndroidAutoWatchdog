package nl.weekplanner.ah;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.webkit.*;
import android.widget.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(247,247,243);
    private static final int INK = Color.rgb(31,37,33);
    private static final int MUTED = Color.rgb(101,108,103);
    private static final int GREEN = Color.rgb(42,110,73);
    private static final int GREEN_LIGHT = Color.rgb(228,240,232);
    private static final int ORANGE = Color.rgb(232,132,49);
    private static final int CARD = Color.WHITE;

    private LinearLayout content;
    private final PlannerEngine planner = new PlannerEngine();
    private AhClient ah;
    private SharedPreferences prefs;
    private HouseholdManager household;
    private List<PlannerEngine.Meal> currentWeek = new ArrayList<>();
    private LocalDate currentMonday;
    private final Set<String> pantry = new LinkedHashSet<>();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(22,55,39));
        getWindow().setNavigationBarColor(Color.rgb(22,55,39));
        ah = new AhClient(this);
        prefs = getSharedPreferences("planner_settings", MODE_PRIVATE);
        household = new HouseholdManager(prefs);
        loadPantry();
        handleCallback(getIntent());
        selectDefaultWeek();
        showWeek();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCallback(intent);
    }

    private void selectDefaultWeek() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue()-1);
        if (today.getDayOfWeek() == DayOfWeek.SUNDAY && prefs.getBoolean("sunday_next", true)) monday = monday.plusWeeks(1);
        currentMonday = monday;
        currentWeek = generateWeek();
    }

    private void buildShell(String subtitle, int selectedTab) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(22), dp(18), dp(22), dp(18));
        hero.setBackgroundColor(Color.rgb(22,55,39));
        hero.addView(text("MT Food", 27, true, Color.WHITE));
        hero.addView(text(subtitle, 13, false, Color.rgb(208,224,215)));
        root.addView(hero);

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(10),dp(8),dp(10),dp(8));
        nav.setBackgroundColor(Color.WHITE);
        nav.addView(tab("Week", "📅", selectedTab==0, v->showWeek()));
        nav.addView(tab("Boodschappen", "🛒", selectedTab==1, v->showGroceries()));
        nav.addView(tab("AH", "🔗", selectedTab==2, v->showAh()));
        nav.addView(tab("Instellingen", "⚙", selectedTab==3, v->showSettings()));
        navScroll.addView(nav);
        root.addView(navScroll);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16),dp(14),dp(16),dp(34));
        sv.addView(content);
        root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void showWeek() {
        int week = currentMonday.get(WeekFields.ISO.weekOfWeekBasedYear());
        String range = currentMonday.format(DateTimeFormatter.ofPattern("d MMM")) + " – " + currentMonday.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM"));
        buildShell("Jullie complete eetweek • ontbijt, lunch en avondeten", 0);

        LinearLayout top = card(GREEN_LIGHT);
        top.addView(text("Week " + week + "  •  " + range, 20, true, INK));
        top.addView(text(household.profileSummary() + "  •  " + prefs.getString("diet_style","Gezond & gevarieerd"), 13, false, MUTED));
        LinearLayout arrows = new LinearLayout(this);
        arrows.setOrientation(LinearLayout.HORIZONTAL);
        arrows.setGravity(Gravity.CENTER_VERTICAL);
        Button prev = miniButton("‹ Vorige");
        prev.setOnClickListener(v->{currentMonday=currentMonday.minusWeeks(1);currentWeek=generateWeek();showWeek();});
        Button next = miniButton("Volgende ›");
        next.setOnClickListener(v->{currentMonday=currentMonday.plusWeeks(1);currentWeek=generateWeek();showWeek();});
        arrows.addView(prev,new LinearLayout.LayoutParams(0,-2,1));
        arrows.addView(space(dp(8)));
        arrows.addView(next,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(arrows);
        content.addView(top);

        for (PlannerEngine.Meal m: currentWeek) content.addView(mealCard(m));

        Button generate = primary("✨  Genereer een ander weekmenu");
        generate.setOnClickListener(v->{currentWeek=generateWeek();showWeek();});
        content.addView(generate);
        Button list = secondary("🛒  Maak complete boodschappenlijst");
        list.setOnClickListener(v->showGroceries());
        content.addView(list);
    }

    private View mealCard(PlannerEngine.Meal m) {
        LinearLayout c = card(CARD);
        c.setClickable(true);
        c.setFocusable(true);
        c.addView(text(m.day + "  •  " + m.date.format(DateTimeFormatter.ofPattern("d MMM")), 14, true, GREEN));

        int breakfastCount=household.count(m.date,HouseholdManager.BREAKFAST);
        int lunchCount=household.count(m.date,HouseholdManager.LUNCH);
        int dinnerCount=household.count(m.date,HouseholdManager.DINNER);
        String breakfast=prefs.getString("breakfast","yoghurt / kwark, havermout, blauwe bessen");
        String lunch=prefs.getString("lunch","volkoren brood, eieren, hummus, komkommer");

        c.addView(mealLine("🥣","Ontbijt",breakfastCount,breakfast));
        c.addView(mealLine("🥪","Lunch",lunchCount,lunch));

        LinearLayout dinner = new LinearLayout(this);
        dinner.setOrientation(LinearLayout.HORIZONTAL);
        dinner.setGravity(Gravity.CENTER_VERTICAL);
        dinner.setPadding(0,dp(7),0,0);
        TextView emoji = text(m.emoji, 27, false, INK);
        emoji.setGravity(Gravity.CENTER);
        dinner.addView(emoji,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(8),0,0,0);
        labels.addView(text("Avondeten" + (dinnerCount>0 ? "  •  "+dinnerCount+" personen" : "  •  niet nodig"), 12, true, ORANGE));
        labels.addView(text(m.title, 17, true, INK));
        labels.addView(text(m.category + "  •  " + m.minutes + " min", 12, false, MUTED));
        dinner.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        dinner.addView(text("›",26,false,MUTED));
        c.addView(dinner);
        c.setOnClickListener(v->showRecipe(m));
        return c;
    }

    private View mealLine(String emoji,String label,int count,String items){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0,dp(7),0,dp(7));
        row.addView(text(emoji,21,false,INK),new LinearLayout.LayoutParams(dp(46),-2));
        LinearLayout labels=new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(label+(count>0?"  •  "+count+" personen":"  •  niet nodig"),12,true,count>0?GREEN:MUTED));
        labels.addView(text(count>0?items:"—",14,false,count>0?INK:MUTED));
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        return row;
    }

    private void showRecipe(PlannerEngine.Meal m) {
        buildShell(m.day + " • " + household.count(m.date,HouseholdManager.DINNER) + " personen", 0);
        LinearLayout hero = card(GREEN_LIGHT);
        hero.addView(text(m.emoji + "  " + m.title,22,true,INK));
        hero.addView(text(m.description,14,false,MUTED));
        hero.addView(chip(m.category + "   ·   " + m.minutes + " min"));
        content.addView(hero);
        content.addView(section("Ingrediënten"));
        for (PlannerEngine.Ingredient ing:m.ingredients) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(dp(4),dp(7),dp(4),dp(7));
            TextView n=text((ing.pantry?"◦ ":"• ")+ing.name,15,false,INK);
            TextView a=text(ing.amount,14,false,MUTED);
            a.setGravity(Gravity.END);
            line.addView(n,new LinearLayout.LayoutParams(0,-2,1));
            line.addView(a,new LinearLayout.LayoutParams(dp(145),-2));
            content.addView(line);
        }
        content.addView(text("◦ = meestal voorraadartikel; alleen kopen als het op is.",12,false,MUTED));
        content.addView(section("Bereiding"));
        int nr=1;
        for(String step:m.steps){
            LinearLayout s=card(CARD);
            s.addView(text(nr+".  "+step,15,false,INK));
            content.addView(s);
            nr++;
        }
        Button replace=secondary("↻  Vervang dit gerecht");
        replace.setOnClickListener(v->{currentWeek=generateWeek();showWeek();});
        content.addView(replace);
        Button groceries=primary("🛒  Naar boodschappenlijst");
        groceries.setOnClickListener(v->showGroceries());
        content.addView(groceries);
    }

    private void showGroceries() {
        buildShell("Selecteer wat je echt naar AH wilt sturen", 1);
        if(currentWeek.isEmpty()) currentWeek=generateWeek();
        LinkedHashMap<String,String> all=new LinkedHashMap<>();
        for(PlannerEngine.Meal m:currentWeek) {
            if(household.count(m.date,HouseholdManager.DINNER)<=0) continue;
            for(PlannerEngine.Ingredient ing:m.ingredients) {
                if(!ing.pantry || !pantry.contains(ing.name.toLowerCase())) all.putIfAbsent(ing.name,ing.amount);
            }
        }
        int breakfastPortions=household.totalMealPortions(currentMonday,HouseholdManager.BREAKFAST);
        int lunchPortions=household.totalMealPortions(currentMonday,HouseholdManager.LUNCH);
        if(breakfastPortions>0){
            String breakfast=prefs.getString("breakfast","yoghurt / kwark, havermout, blauwe bessen");
            for(String x:breakfast.split(",")) if(!x.trim().isEmpty()) all.putIfAbsent(x.trim(),"voor "+breakfastPortions+" ontbijt-porties");
        }
        if(lunchPortions>0){
            String lunch=prefs.getString("lunch","volkoren brood, eieren, hummus, komkommer");
            for(String x:lunch.split(",")) if(!x.trim().isEmpty()) all.putIfAbsent(x.trim(),"voor "+lunchPortions+" lunch-porties");
        }
        all.putIfAbsent("Kinder Bueno","1 multipack");
        all.putIfAbsent("fruit","voor 7 dagen");
        all.putIfAbsent("ongezouten noten","1 zak");

        LinearLayout info=card(GREEN_LIGHT);
        info.addView(text(all.size()+" artikelen",20,true,INK));
        info.addView(text("Alleen aangevinkte artikelen worden naar Albert Heijn gestuurd.",13,false,MUTED));
        content.addView(info);

        final List<CheckBox> checks=new ArrayList<>();
        final List<String> names=new ArrayList<>(all.keySet());
        LinearLayout tools=new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        Button selectAll=miniButton("Alles selecteren");
        Button clearAll=miniButton("Alles wissen");
        tools.addView(selectAll,new LinearLayout.LayoutParams(0,-2,1));
        tools.addView(space(dp(8)));
        tools.addView(clearAll,new LinearLayout.LayoutParams(0,-2,1));
        content.addView(tools);

        TextView counter=text("0 geselecteerd",13,true,GREEN);
        counter.setPadding(dp(2),dp(9),0,dp(8));
        content.addView(counter);

        Button ahBtn=primary(ah.isConnected()?"🛒  0 geselecteerd naar AH":"🔗  Koppel eerst Albert Heijn");
        Runnable updateCount=()->{
            int n=0; for(CheckBox cb:checks) if(cb.isChecked()) n++;
            counter.setText(n+" geselecteerd");
            if(ah.isConnected()) ahBtn.setText("🛒  "+n+" geselecteerd naar AH");
            ahBtn.setEnabled(!ah.isConnected() || n>0);
            ahBtn.setAlpha((!ah.isConnected() || n>0)?1f:0.45f);
        };

        for(Map.Entry<String,String> e:all.entrySet()){
            CheckBox cb=new CheckBox(this);
            cb.setText(e.getKey()+"   ·   "+e.getValue());
            cb.setTextSize(15); cb.setTextColor(INK); cb.setPadding(dp(2),dp(7),0,dp(7));
            cb.setOnCheckedChangeListener((buttonView,isChecked)->updateCount.run());
            checks.add(cb); content.addView(cb);
        }
        selectAll.setOnClickListener(v->{for(CheckBox cb:checks)cb.setChecked(true);updateCount.run();});
        clearAll.setOnClickListener(v->{for(CheckBox cb:checks)cb.setChecked(false);updateCount.run();});
        ahBtn.setOnClickListener(v->{
            if(!ah.isConnected()){showAh();return;}
            List<String> selected=new ArrayList<>();
            for(int i=0;i<checks.size();i++) if(checks.get(i).isChecked()) selected.add(names.get(i));
            if(selected.isEmpty()){Toast.makeText(this,"Selecteer eerst minimaal één artikel",Toast.LENGTH_SHORT).show();return;}
            new AlertDialog.Builder(this)
                    .setTitle("Naar Albert Heijn")
                    .setMessage(selected.size()+" artikel"+(selected.size()==1?"":"en")+" toevoegen aan je AH-lijst?")
                    .setPositiveButton("Toevoegen",(d,w)->matchAndAdd(selected))
                    .setNegativeButton("Annuleren",null).show();
        });
        updateCount.run();
        content.addView(ahBtn);
    }

    private void showAh() {
        buildShell("Koppeling en veilige praktijktest • v1.1", 2);
        LinearLayout status=card(ah.isConnected()?GREEN_LIGHT:Color.rgb(250,237,224));
        status.addView(text(ah.isConnected()?"✓ Albert Heijn gekoppeld":"Albert Heijn nog niet gekoppeld",20,true,ah.isConnected()?GREEN:ORANGE));
        status.addView(text(ah.isConnected()?"De login is lokaal versleuteld opgeslagen. Test eerst de verbinding voordat je producten toevoegt.":"Log in via Albert Heijn. Je wachtwoord wordt niet door deze app opgeslagen.",14,false,MUTED));
        content.addView(status);
        if(!ah.isConnected()){
            Button login=primary("Inloggen bij Albert Heijn");
            login.setOnClickListener(v->showLoginWebView());
            content.addView(login);
        } else {
            Button test=primary("✓  Test AH-koppeling");
            test.setOnClickListener(v->testAh());
            content.addView(test);
            Button open=secondary("Open officiële AH-app");
            open.setOnClickListener(v->openAh());
            content.addView(open);
            Button logout=secondary("Koppeling verwijderen");
            logout.setOnClickListener(v->{ah.disconnect();showAh();});
            content.addView(logout);
        }
        LinearLayout warning=card(Color.rgb(255,247,224));
        warning.addView(text("Let op",15,true,Color.rgb(133,92,26)));
        warning.addView(text("De mobiele AH-interface is niet bedoeld als publieke ontwikkelaars-API. Daarom controleert de app de verbinding expliciet en blijft de definitieve bestelling altijd in de officiële AH-omgeving.",13,false,MUTED));
        content.addView(warning);
    }

    private void testAh() {
        Waiting d=new Waiting(this,"Verbinding met AH testen…");
        d.show();
        Executors.newSingleThreadExecutor().execute(()->{
            try{
                ah.testConnection();
                runOnUiThread(()->{
                    d.dismiss();
                    new AlertDialog.Builder(this).setTitle("AH-koppeling werkt").setMessage("De app kan je ingelogde AH-omgeving bereiken. Er is niets aan je boodschappenlijst toegevoegd.").setPositiveButton("OK",null).show();
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    d.dismiss();
                    new AlertDialog.Builder(this).setTitle("AH-test mislukt").setMessage(e.getMessage()).setPositiveButton("OK",null).show();
                });
            }
        });
    }

    private void matchAndAdd(List<String> names) {
        Waiting d=new Waiting(this,"AH-producten zoeken en toevoegen…");
        d.show();
        Executors.newSingleThreadExecutor().execute(()->{
            try{
                List<AhClient.AhLine> lines=new ArrayList<>();
                int missed=0;
                for(String name:names){
                    List<AhClient.AhProduct> found=ah.searchProducts(name,1);
                    if(!found.isEmpty()) lines.add(new AhClient.AhLine(found.get(0).id,1)); else missed++;
                }
                if(lines.isEmpty()) throw new RuntimeException("Geen AH-producten gevonden.");
                ah.addProductsToShoppingList(lines);
                final int m=missed;
                runOnUiThread(()->{
                    d.dismiss();
                    new AlertDialog.Builder(this).setTitle("Toegevoegd aan AH").setMessage(lines.size()+" producten toegevoegd"+(m>0?"; "+m+" niet automatisch gevonden.":".")+" Controleer merken, hoeveelheden en verpakkingsgroottes in de AH-app.").setPositiveButton("Open AH",(x,y)->openAh()).setNegativeButton("Later",null).show();
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    d.dismiss();
                    new AlertDialog.Builder(this).setTitle("AH-koppeling").setMessage(e.getMessage()).setPositiveButton("OK",null).show();
                });
            }
        });
    }

    private void showSettings() {
        buildShell("Huishouden, 2-wekenrooster en voedingsprofiel", 3);

        content.addView(section("Huishouden"));
        LinearLayout house=card(GREEN_LIGHT);
        house.addView(text("👥  "+household.people().size()+" personen",18,true,INK));
        house.addView(text("Per persoon kun je leeftijd, man/vrouw/kind, medische aandachtspunten en aanwezigheid voor ontbijt, lunch en avondeten over twee weken instellen.",13,false,MUTED));
        content.addView(house);

        for(int i=0;i<household.people().size();i++){
            final int pos=i;
            HouseholdManager.Person p=household.people().get(i);
            LinearLayout pc=card(CARD);
            pc.addView(text(profileEmoji(p)+"  "+p.name+"  •  "+p.type+"  •  "+p.age+" jaar",16,true,INK));
            pc.addView(text(p.healthSummary(),13,false,MUTED));
            Button edit=secondary("Bewerk profiel & 2-wekenrooster");
            edit.setOnClickListener(v->editPerson(pos));
            pc.addView(edit);
            content.addView(pc);
        }
        Button addPerson=secondary("＋  Persoon toevoegen");
        addPerson.setOnClickListener(v->addPerson());
        content.addView(addPerson);

        content.addView(section("Voedingsstijl"));
        Spinner diet=new Spinner(this);
        String[] diets={
                "Gezond & gevarieerd","Mediterraan","DASH","MIND","Nordic","Portfolio",
                "Vegetarisch","Veganistisch","Koolhydraatarm","Ketogeen","Low-FODMAP"
        };
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,diets);
        diet.setAdapter(adapter);
        String selectedDiet=prefs.getString("diet_style","Gezond & gevarieerd");
        for(int i=0;i<diets.length;i++)if(diets[i].equals(selectedDiet))diet.setSelection(i);
        content.addView(diet);
        LinearLayout evidence=card(Color.rgb(255,247,224));
        evidence.addView(text("Evidence-informed, niet automatisch medisch voorschrift",14,true,Color.rgb(133,92,26)));
        evidence.addView(text("Deze voedingsstijlen hebben onderzoeksonderbouwing in specifieke contexten. Ketogeen en low-FODMAP zijn niet voor iedereen geschikt; de app behandelt ze als voorkeur en waarschuwt bij profielen waar extra voorzichtigheid nodig is.",12,false,MUTED));
        content.addView(evidence);

        content.addView(section("Gezamenlijke voedingsaccenten"));
        LinearLayout accents=card(CARD);
        Set<String> flags=household.nutritionFlags();
        accents.addView(text(flags.isEmpty()?"Geen extra medische aandachtspunten":android.text.TextUtils.join(" • ",flags),14,true,GREEN));
        accents.addView(text("De planner gebruikt deze gegevens om de weekkeuzes te sturen richting o.a. voldoende eiwit, vezels, calciumrijke keuzes, vis/onverzadigde vetten en minder sterk bewerkt voedsel. Er worden geen medische behandelclaims gedaan.",12,false,MUTED));
        content.addView(accents);

        content.addView(section("Vaste ontbijtartikelen"));
        EditText breakfast=input("Ontbijt (komma's)",prefs.getString("breakfast","yoghurt / kwark, havermout, blauwe bessen"));
        content.addView(breakfast);
        content.addView(section("Vaste lunchartikelen"));
        EditText lunch=input("Lunchartikelen (komma's)",prefs.getString("lunch","volkoren brood, eieren, hummus, komkommer"));
        content.addView(lunch);
        content.addView(section("Favorieten"));
        EditText fav=input("Favoriete gerechten/producten",prefs.getString("favorites","Knorr Kip Madras, Mexicaanse wraps, Kinder Bueno"));
        content.addView(fav);
        content.addView(section("Verbodenlijst"));
        EditText banned=input("Nooit gebruiken (komma's)",prefs.getString("banned",""));
        content.addView(banned);
        content.addView(section("Voorraad"));
        EditText stock=input("Standaard in huis (komma's)",prefs.getString("pantry","olijfolie, zwarte peper, zout, paprikapoeder, knoflookpoeder, oregano, basilicum, mosterd, sojasaus, ketjap, currypasta"));
        stock.setMinLines(3); content.addView(stock);
        content.addView(section("Planning"));
        CheckBox sunday=new CheckBox(this);
        sunday.setText("Op zondag standaard volgende week tonen");
        sunday.setChecked(prefs.getBoolean("sunday_next",true)); sunday.setTextColor(INK); content.addView(sunday);
        EditText cook=input("Maximale kooktijd (minuten)",String.valueOf(prefs.getInt("cook",35)));
        cook.setInputType(2); content.addView(cook);
        Button save=primary("Opslaan");
        save.setOnClickListener(v->{
            int mins=35; try{mins=Integer.parseInt(cook.getText().toString());}catch(Exception ignored){}
            prefs.edit().putString("breakfast",breakfast.getText().toString())
                    .putString("lunch",lunch.getText().toString())
                    .putString("favorites",fav.getText().toString())
                    .putString("banned",banned.getText().toString())
                    .putString("pantry",stock.getText().toString())
                    .putString("diet_style",String.valueOf(diet.getSelectedItem()))
                    .putBoolean("sunday_next",sunday.isChecked()).putInt("cook",mins).apply();
            household.save(); loadPantry(); currentWeek=generateWeek();
            Toast.makeText(this,"Instellingen opgeslagen",Toast.LENGTH_SHORT).show();
            showSettings();
        });
        content.addView(save);
    }

    private String profileEmoji(HouseholdManager.Person p){
        if("Kind".equals(p.type))return "🧒";
        return "Vrouw".equals(p.type)?"👩":"👨";
    }

    private void addPerson(){
        HouseholdManager.Person p=new HouseholdManager.Person("Nieuwe persoon","Man",40);
        for(int d=0;d<14;d++){p.schedule[d][0]=true;p.schedule[d][2]=true;}
        household.people().add(p); household.save(); editPerson(household.people().size()-1);
    }

    private void editPerson(int index){
        if(index<0||index>=household.people().size())return;
        HouseholdManager.Person p=household.people().get(index);
        ScrollView sv=new ScrollView(this);
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18),dp(8),dp(18),dp(12)); sv.addView(box);
        EditText name=input("Naam",p.name); box.addView(name);
        Spinner type=new Spinner(this);
        String[] types={"Man","Vrouw","Kind"};
        type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,types));
        for(int i=0;i<types.length;i++)if(types[i].equals(p.type))type.setSelection(i);
        box.addView(type);
        EditText age=input("Leeftijd",String.valueOf(p.age)); age.setInputType(2); box.addView(age);

        box.addView(text("Medische/voedingsaandacht",16,true,INK));
        CheckBox menopause=profileCheck("Overgang / menopauze",p.menopause);box.addView(menopause);
        CheckBox skin=profileCheck("Huidaandoening / psoriasis",p.skin);box.addView(skin);
        CheckBox heart=profileCheck("Hart- en vaatgezondheid",p.heart);box.addView(heart);
        CheckBox glucose=profileCheck("Glucose / diabetes-aandacht",p.glucose);box.addView(glucose);
        CheckBox bp=profileCheck("Bloeddruk",p.bloodPressure);box.addView(bp);
        CheckBox bone=profileCheck("Botgezondheid",p.bone);box.addView(bone);
        CheckBox muscle=profileCheck("Spiermassa / extra eiwitaandacht",p.muscle);box.addView(muscle);

        box.addView(text("Aanwezigheid • Week A = oneven, Week B = even",16,true,INK));
        String[] days={"Ma","Di","Wo","Do","Vr","Za","Zo"};
        final CheckBox[][] schedule=new CheckBox[14][3];
        for(int d=0;d<14;d++){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
            String prefix=d<7?"A ":"B ";
            TextView day=text(prefix+days[d%7],13,true,INK);row.addView(day,new LinearLayout.LayoutParams(dp(58),-2));
            String[] shortMeal={"Ontb","Lunch","Avond"};
            for(int m=0;m<3;m++){
                CheckBox cb=new CheckBox(this);cb.setText(shortMeal[m]);cb.setTextSize(11);cb.setTextColor(INK);cb.setChecked(p.schedule[d][m]);schedule[d][m]=cb;
                row.addView(cb,new LinearLayout.LayoutParams(0,-2,1));
            }
            box.addView(row);
        }

        AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Persoon bewerken").setView(sv)
                .setPositiveButton("Opslaan",null).setNegativeButton("Annuleren",null)
                .setNeutralButton("Verwijderen",null).create();
        dlg.setOnShowListener(x->{
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                p.name=name.getText().toString().trim().isEmpty()?"Persoon":name.getText().toString().trim();
                p.type=String.valueOf(type.getSelectedItem());
                try{p.age=Integer.parseInt(age.getText().toString());}catch(Exception ignored){}
                p.menopause=menopause.isChecked();p.skin=skin.isChecked();p.heart=heart.isChecked();p.glucose=glucose.isChecked();p.bloodPressure=bp.isChecked();p.bone=bone.isChecked();p.muscle=muscle.isChecked();
                for(int d=0;d<14;d++)for(int m=0;m<3;m++)p.schedule[d][m]=schedule[d][m].isChecked();
                household.save(); currentWeek=generateWeek(); dlg.dismiss(); showSettings();
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                if(household.people().size()<=1){Toast.makeText(this,"Er moet minimaal één persoon blijven",Toast.LENGTH_SHORT).show();return;}
                new AlertDialog.Builder(this).setTitle("Persoon verwijderen?").setMessage(p.name+" verwijderen uit het huishouden?")
                        .setPositiveButton("Verwijderen",(a,b)->{household.people().remove(index);household.save();dlg.dismiss();showSettings();})
                        .setNegativeButton("Annuleren",null).show();
            });
        });
        dlg.show();
    }

    private CheckBox profileCheck(String label,boolean checked){
        CheckBox c=new CheckBox(this);c.setText(label);c.setTextColor(INK);c.setTextSize(14);c.setChecked(checked);return c;
    }

    private void loadPantry(){
        pantry.clear();
        String s=getSharedPreferences("planner_settings",MODE_PRIVATE).getString("pantry","olijfolie, zwarte peper, zout, paprikapoeder, knoflookpoeder, oregano, basilicum, mosterd, sojasaus, ketjap, currypasta");
        for(String x:s.split(",")) if(!x.trim().isEmpty()) pantry.add(x.trim().toLowerCase());
    }

    private List<PlannerEngine.Meal> generateWeek(){
        Set<String> banned=new LinkedHashSet<>();
        for(String x:prefs.getString("banned","").split(",")) if(!x.trim().isEmpty()) banned.add(x.trim());

        String diet=prefs.getString("diet_style","Gezond & gevarieerd");
        if("Vegetarisch".equals(diet) || "Veganistisch".equals(diet)){
            Collections.addAll(banned,"kipfilet","kipdij","zalm","kabeljauw","rundergehakt","gehaktbal");
        }
        if("Veganistisch".equals(diet)){
            Collections.addAll(banned,"yoghurt","kaas","melk","feta","room");
        }
        int max=prefs.getInt("cook",35);
        return planner.makeWeek(currentMonday,banned,max);
    }

    private void showLoginWebView(){
        WebView web=new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                Uri u=req.getUrl();
                if("appie".equals(u.getScheme())&&"login-exit".equals(u.getHost())){
                    String code=u.getQueryParameter("code");
                    if(code!=null)exchange(code);
                    return true;
                }
                return false;
            }
        });
        web.loadUrl(ah.getLoginUrl());
        setContentView(web);
    }

    private void handleCallback(Intent intent){
        Uri u=intent.getData();
        if(u!=null&&"appie".equals(u.getScheme())&&"login-exit".equals(u.getHost())){
            String code=u.getQueryParameter("code");
            if(code!=null)exchange(code);
        }
    }

    private void exchange(String code){
        Executors.newSingleThreadExecutor().execute(()->{
            try{
                ah.exchangeCode(code);
                runOnUiThread(()->{Toast.makeText(this,"Albert Heijn gekoppeld",Toast.LENGTH_LONG).show();showAh();});
            }catch(Exception e){
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("AH login mislukt").setMessage(e.getMessage()).setPositiveButton("OK",null).show());
            }
        });
    }

    private void openAh(){
        try{
            Intent i=getPackageManager().getLaunchIntentForPackage("com.ahold.mobile.ah");
            if(i!=null)startActivity(i); else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.ah.nl/mijnlijst")));
        }catch(Exception e){
            startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.ah.nl/mijnlijst")));
        }
    }

    private View tab(String label,String icon,boolean selected,View.OnClickListener l){
        TextView v=text(icon+"  "+label,14,selected,selected?GREEN:INK);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14),dp(10),dp(14),dp(10));
        v.setBackground(round(selected?GREEN_LIGHT:Color.TRANSPARENT,dp(18)));
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);
        lp.setMargins(dp(3),0,dp(3),0);
        v.setLayoutParams(lp);
        return v;
    }
    private LinearLayout card(int color){
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16),dp(14),dp(16),dp(14));
        c.setBackground(round(color,dp(16)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,0,0,dp(12));
        c.setLayoutParams(lp);
        c.setElevation(dp(1));
        return c;
    }
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius);return g;}
    private TextView text(String s,int sp,boolean bold,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private TextView section(String s){TextView v=text(s,17,true,INK);v.setPadding(dp(2),dp(8),0,dp(9));return v;}
    private TextView chip(String s){TextView v=text(s,13,true,GREEN);v.setPadding(dp(10),dp(6),dp(10),dp(6));v.setBackground(round(Color.WHITE,dp(14)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMargins(0,dp(10),0,0);v.setLayoutParams(lp);return v;}
    private Button primary(String s){Button b=new Button(this);b.setText(s);b.setTextSize(16);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(round(GREEN,dp(14)));b.setPadding(dp(14),dp(12),dp(14),dp(12));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.setMargins(0,dp(6),0,dp(10));b.setLayoutParams(lp);return b;}
    private Button secondary(String s){Button b=primary(s);b.setTextColor(GREEN);b.setBackground(round(GREEN_LIGHT,dp(14)));return b;}
    private Button miniButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(GREEN);b.setBackground(round(Color.WHITE,dp(12)));return b;}
    private EditText input(String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setTextSize(15);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setBackground(round(Color.WHITE,dp(12)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(12));e.setLayoutParams(lp);return e;}
    private Space space(int w){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(w,1));return s;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}

    static class Waiting {
        private final AlertDialog d;
        Waiting(Activity a,String msg){
            LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.HORIZONTAL);box.setGravity(Gravity.CENTER_VERTICAL);box.setPadding(40,28,40,28);
            ProgressBar p=new ProgressBar(a);TextView t=new TextView(a);t.setText(msg);t.setTextSize(15);t.setPadding(24,0,0,0);box.addView(p);box.addView(t);
            d=new AlertDialog.Builder(a).setView(box).setCancelable(false).create();
        }
        void show(){d.show();}
        void dismiss(){if(d.isShowing())d.dismiss();}
    }
}
