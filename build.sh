#!/bin/bash

#!/bin/bash

# Check if the target directory exists, create if it doesn't
if [ ! -d "target" ]; then
  echo "Creating target directory..."
  mkdir -p target
else
  echo "Target directory already exists."
fi

# Check if commonmark.jar exists, if not download it
if [ ! -f "target/commonmark.jar" ]; then
  echo "Downloading commonmark.jar..."
  curl https://repo1.maven.org/maven2/org/commonmark/commonmark/0.19.0/commonmark-0.19.0.jar -o target/commonmark.jar
else
  echo "commonmark.jar already exists, skipping download."
fi

# Check if commonmark-ext-gfm-tables.jar exists, if not download it
if [ ! -f "target/commonmark-ext-gfm-tables.jar" ]; then
  echo "Downloading commonmark-ext-gfm-tables.jar..."
  curl https://repo1.maven.org/maven2/org/commonmark/commonmark-ext-gfm-tables/0.19.0/commonmark-ext-gfm-tables-0.19.0.jar -o target/commonmark-ext-gfm-tables.jar
else
  echo "commonmark-ext-gfm-tables.jar already exists, skipping download."
fi

# Download Jackson Core jar if it doesn't exist
if [ ! -f "target/jackson-core.jar" ]; then
  echo "Downloading jackson-core.jar..."
  curl https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.18.0/jackson-core-2.18.0.jar -o target/jackson-core.jar
else
  echo "jackson-core.jar already exists, skipping download."
fi

# Download Jackson Databind jar if it doesn't exist
if [ ! -f "target/jackson-databind.jar" ]; then
  echo "Downloading jackson-databind.jar..."
  curl https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.18.0/jackson-databind-2.18.0.jar -o target/jackson-databind.jar
else
  echo "jackson-databind.jar already exists, skipping download."
fi

# Download Jackson Annotations jar if it doesn't exist
if [ ! -f "target/jackson-annotations.jar" ]; then
  echo "Downloading jackson-annotations.jar..."
  curl https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.18.0/jackson-annotations-2.18.0.jar -o target/jackson-annotations.jar
else
  echo "jackson-annotations.jar already exists, skipping download."
fi

# Run jshell with the specified classpath and startup file
echo 'Runner.main();/exit' | jshell --class-path target/commonmark.jar:target/commonmark-ext-gfm-tables.jar:target/jackson-databind.jar:target/jackson-core.jar:target/jackson-annotations.jar --startup site.generator.java
