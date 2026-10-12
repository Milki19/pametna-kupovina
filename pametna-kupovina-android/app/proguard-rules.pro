# Kod je javan na GitHub-u, pa zamućivanje imena ništa ne krije — a izveštaj o
# padu sa telefona mora da se čita bez mapping fajla. R8 i dalje izbacuje
# neiskorišćen kod i resurse.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# ML Kit (Google-ov skener za račune i domaćinstvo) pravi svoje delove kroz
# refleksiju, praznim konstruktorom. R8 ih je izbacivao, pa se skener u
# release verziji nije ni otvarao.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# Retrofit čita tip odgovora iz potpisa metode. Kad aplikacija odgovor ne
# koristi (prijava proizvoda, „Javi da ovde nema“), R8 je taj tip izbacivao i
# poziv je u release verziji padao pre nego što stigne do servera.
-keep class rs.pametnakupovina.app.data.network.**
