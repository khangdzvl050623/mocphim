import Header from "@/layouts/Header";
import Footer from "@/layouts/Footer";
import { Preloader } from "@/components/Preloader";
import VerifyEmailBanner from "@/components/VerifyEmailBanner";
export default function RootLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {
    return (
        
        <div className="flex flex-col min-h-screen bg-[#f0f3f8] dark:bg-[#191B24] transition-colors duration-300"> 
            <Preloader />
            <Header />
            <VerifyEmailBanner />
                <main className="flex-grow">
                    {children}
                </main>
            <Footer />
        </div>
    );
}
