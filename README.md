# guard-work

## Project Structure

`  guard-work/
├── .env                              # big-shared dotenv
├── .gitignore                        
├── infra/                            # infra
│   ├── docker-compose.yml            
│   └── ...                     
│
├── backend/                          
│   ├── pom.xml                       
│   ├── core/                  
│   │   ├── src/
│   │   └── pom.xml
│   ├── shared/                  
│   │   ├── src/
│   │   └── pom.xml
│   └── api/                      
│       ├── src/
│       └── pom.xml                   
│   ....
└── frontend/                         # plan 
    ├── package.json                  
    ├── apps/
    │   └── web-app/                  
    │       ├── src/
    │       ├── package.json
    │       └── vite.config.ts        
    └── packages/                     
        ├── ui/                       
        │   └── package.json
        ├── utils/                    
        │   └── package.json
        └── configs/                  
            ├── eslint-config/
            │   ├── index.js
            │   └── package.json
            ├── prettier-config/
            │   ├── .prettierrc
            │   └── package.json
            └── ts-config/
                ├── tsconfig.base.json
                └── package.json`
