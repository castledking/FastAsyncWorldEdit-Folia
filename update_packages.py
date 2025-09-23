import os
import re

def update_file(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Update package and import statements
    updated_content = re.sub(
        r'com\.sk89q\.worldedit\.bukkit\.adapter\.impl\.fawe\.v1_21_6',
        'com.sk89q.worldedit.bukkit.adapter.impl.fawe.v1_21_8',
        content
    )
    
    if updated_content != content:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(updated_content)
        print(f"Updated: {file_path}")
    else:
        print(f"No changes needed: {file_path}")

def main():
    base_dir = r"c:\Users\Towki\Repositories\FastAsyncWorldEdit\worldedit-bukkit\adapters\adapter-1_21_8\src\main\java\com\sk89q\worldedit\bukkit\adapter\impl\fawe\v1_21_8"
    
    for root, _, files in os.walk(base_dir):
        for file in files:
            if file.endswith('.java'):
                file_path = os.path.join(root, file)
                update_file(file_path)

if __name__ == "__main__":
    main()
